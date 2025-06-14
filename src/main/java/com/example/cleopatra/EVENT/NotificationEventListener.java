package com.example.cleopatra.EVENT;

import com.example.cleopatra.config.NotificationWebSocketHandler;
import com.example.cleopatra.model.Notification;
import com.example.cleopatra.model.User;
import com.example.cleopatra.repository.NotificationRepository;
import com.example.cleopatra.dto.Notification.NotificationDto;
import com.example.cleopatra.maper.NotificationMapper;
import com.example.cleopatra.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
     private final NotificationWebSocketHandler notificationWebSocketHandler;
    private final NotificationService notificationService;



    @EventListener
    @Async
    @Transactional(readOnly = true)
    public void handleNotificationCreated(NotificationCreatedEvent event) {
        log.info("🎉 EVENT RECEIVED: NotificationCreatedEvent for ID: {} to recipient: {}",
                event.getNotificationId(), event.getRecipientId());

        try {
            // Загружаем уведомление с eager loading
            Notification notification = notificationRepository.findByIdWithUsers(event.getNotificationId())
                    .orElse(null);

            if (notification == null) {
                log.warn("⚠️ Notification not found: {}", event.getNotificationId());
                return;
            }

            log.info("📋 Found notification: title={}", notification.getTitle());

            // Преобразуем в DTO
            NotificationDto dto = notificationMapper.toWebSocketDto(notification);


            // 🔥 ПРИНУДИТЕЛЬНО ИНИЦИАЛИЗИРУЕМ ВСЕ СВЯЗИ
            Hibernate.initialize(notification.getRecipient());
            if (notification.getActor() != null) {
                Hibernate.initialize(notification.getActor());
            }


            // 🔧 ПОПЫТКА ОТПРАВКИ ЧЕРЕЗ WEBSOCKET
            boolean sentViaWebSocket = notificationWebSocketHandler.sendNotificationToUser(
                    event.getRecipientId(),
                    dto
            );

            if (sentViaWebSocket) {
                // ✅ Отправлено через WebSocket
                log.info("📤 Notification sent via WebSocket to user: {}", event.getRecipientId());
                updateNotificationAsSent(event.getNotificationId());
            } else {
                // 🔕 Пользователь оффлайн - проверяем его статус в БД
                log.info("🔕 User {} not connected to WebSocket, checking online status", event.getRecipientId());

                User recipient = notification.getRecipient();
                if (Boolean.TRUE.equals(recipient.getIsOnline())) {
                    // Пользователь онлайн в БД, но не подключен к WebSocket
                    log.info("👤 User {} is online but not connected to WebSocket, will retry later", event.getRecipientId());

                    // Планируем повторную отправку через 30 секунд
                    scheduleRetryNotification(event.getNotificationId(), event.getRecipientId(), dto);
                } else {
                    log.info("💤 User {} is offline, notification will be delivered when online", event.getRecipientId());

                }
            }

            log.info("✅ Notification processing completed for user: {}", event.getRecipientId());

        } catch (Exception e) {
            log.error("❌ Error in event listener for notification: {}", event.getNotificationId(), e);
            e.printStackTrace();
        }
    }


    @EventListener
    @Async
    public void handleSubscriptionCreated(SubscriptionCreatedEvent event) {
        log.info("🎉 EVENT RECEIVED: SubscriptionCreatedEvent - {} subscribed to {}",
                event.getSubscriberId(), event.getSubscribedToId());

        try {
            // Используем ваш существующий метод
            notificationService.createFollowNotification(
                    event.getSubscribedToId(), // кому (на кого подписались)
                    event.getSubscriberId()    // кто (кто подписался)
            );

            log.info("✅ Follow notification created successfully: {} → {}",
                    event.getSubscriberId(), event.getSubscribedToId());
        } catch (Exception e) {
            log.error("❌ Error creating follow notification: {} → {}",
                    event.getSubscriberId(), event.getSubscribedToId(), e);
        }
    }



    @EventListener
    @Async
    public void handleUnsubscribe(UnsubscribeEvent event) {
        log.info("🔔 EVENT RECEIVED: UnsubscribeEvent - {} unsubscribed from {}",
                event.getSubscriberId(), event.getSubscribedToId());

        try {
            // Создаем уведомление об отписке
            notificationService.createUnsubscribeNotification(
                    event.getSubscribedToId(),  // кому уведомление (от кого отписались)
                    event.getSubscriberId(),    // кто отписался
                    event.getSubscriberName()   // имя того, кто отписался
            );

            log.info("✅ Unsubscribe notification created successfully: {} unsubscribed from {}",
                    event.getSubscriberId(), event.getSubscribedToId());

        } catch (Exception e) {
            log.error("❌ Error creating unsubscribe notification: {} unsubscribed from {}",
                    event.getSubscriberId(), event.getSubscribedToId(), e);
        }
    }

    @EventListener
    @Async
    public void handlePostLiked(PostLikedEvent event) {
        log.info("🎉 EVENT RECEIVED: PostLikedEvent for post: {} by user: {}",
                event.getPostId(), event.getLikerUserId());

        try {
            notificationService.createLikeNotification(
                    event.getPostAuthorId(), // кому
                    event.getLikerUserId(),   // кто
                    event.getPostId(),        // какой пост
                    event.getPostTitle()      // название поста
            );

            log.info("✅ Like notification created successfully for post: {}", event.getPostId());
        } catch (Exception e) {
            log.error("❌ Error creating like notification for post: {}", event.getPostId(), e);
        }
    }


    @EventListener
    @Async
    public void handlePostComment(PostCommentEvent event) {
        // Метод createCommentNotification сам управляет транзакцией
        try {
            if (!event.getPostAuthorId().equals(event.getCommenterUserId())) {
                notificationService.createCommentNotification(
                        event.getPostAuthorId(),
                        event.getCommenterUserId(),
                        event.getPostId(),
                        event.getCommentText()
                );

                log.info("Уведомление о комментарии отправлено: пост {}, от пользователя {} к пользователю {}",
                        event.getPostId(), event.getCommenterUserId(), event.getPostAuthorId());
            } else {
                log.debug("Пользователь прокомментировал свой собственный пост, уведомление не отправляется");
            }
        } catch (Exception e) {
            log.error("Ошибка при создании уведомления о комментарии для поста {}: {}",
                    event.getPostId(), e.getMessage(), e);
        }
    }


    // Добавьте этот метод в ваш NotificationEventListener

    @EventListener
    @Async
    public void handleWallPostCreated(WallPostCreatedEvent event) {
        log.info("🎉 EVENT RECEIVED: WallPostCreatedEvent for post: {} by user: {} on wall: {}",
                event.getPostId(), event.getAuthorId(), event.getWallOwnerId());

        try {
            // Проверяем, что автор поста не является владельцем стены
            // (не отправляем уведомление самому себе)
            if (!event.getAuthorId().equals(event.getWallOwnerId())) {

                notificationService.createWallPostNotification(
                        event.getWallOwnerId(),  // кому уведомление (владелец стены)
                        event.getAuthorId(),     // кто создал пост (автор)
                        event.getPostId(),       // ID поста
                        event.getPostText(),     // текст поста
                        event.getPostPicUrl()    // картинка поста (если есть)
                );

                log.info("✅ Wall post notification created successfully: {} posted on {}'s wall",
                        event.getAuthorId(), event.getWallOwnerId());
            } else {
                log.debug("👤 User posted on their own wall, no notification needed");
            }

        } catch (Exception e) {
            log.error("❌ Error creating wall post notification for post: {}", event.getPostId(), e);
        }
    }



    /**
     * Планирует повторную отправку уведомления
     */

    private void scheduleRetryNotification(Long notificationId, Long recipientId, NotificationDto dto) {
        log.info("⏰ Scheduling retry for notification {} to user {} in 30 seconds", notificationId, recipientId);

        // Используем CompletableFuture для отложенной отправки
        CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(() -> {
            try {
                log.info("🔄 Retrying notification delivery for user: {}", recipientId);

                boolean sentViaWebSocket = notificationWebSocketHandler.sendNotificationToUser(recipientId, dto);

                if (sentViaWebSocket) {
                    log.info("✅ Notification delivered on retry to user: {}", recipientId);
                    updateNotificationAsSent(notificationId);
                } else {
                    log.info("🔕 Retry failed - user {} still not connected", recipientId);
                    // Можно запланировать еще одну попытку или оставить как есть
                }

            } catch (Exception e) {
                log.error("❌ Error during notification retry for user: {}", recipientId, e);
            }
        });
    }



    @Transactional
    public void updateNotificationAsSent(Long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(notification -> {
            notification.setIsSent(true);
            notification.setSentAt(LocalDateTime.now());
            notificationRepository.save(notification);
            log.debug("✅ Marked notification {} as sent", notificationId);
        });
    }
}
