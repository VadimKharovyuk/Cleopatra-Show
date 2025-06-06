package com.example.cleopatra.controller;

import com.example.cleopatra.ExistsException.ImageValidationException;
import com.example.cleopatra.dto.Post.PostListDto;
import com.example.cleopatra.dto.user.UpdateProfileDto;
import com.example.cleopatra.dto.user.UserRecommendationDto;
import com.example.cleopatra.dto.user.UserResponse;
import com.example.cleopatra.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


import jakarta.validation.Valid;

import java.util.Collections;
import java.util.List;

@Controller
@RequestMapping("/profile")
@RequiredArgsConstructor
@Slf4j
public class UserProfileController {

    private final UserService userService;
    private final RecommendationService recommendationService;
    private final ImageValidator imageValidator;
    private final SubscriptionService subscriptionService;
    private final IpAddressService ipAddressService;
    private final PostService postService;



    @GetMapping("/{userId}")
    public String showProfile(@PathVariable Long userId,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "6") int size,
                              Model model,
                              HttpServletRequest request,
                              Authentication authentication) {
        try {

            if (authentication != null) {
                UserResponse currentUser = userService.getUserByEmail(authentication.getName());
                model.addAttribute("currentUserId", currentUser.getId());
            }
            // Получаем информацию о пользователе
            UserResponse user = userService.getUserById(userId);
            model.addAttribute("user", user);



            // Получаем посты пользователя
            PostListDto userPosts = postService.getUserPosts(userId, page, size);
            model.addAttribute("posts", userPosts);
            model.addAttribute("currentPage", page);
            model.addAttribute("pageSize", size);


            // Обрабатываем данные текущего пользователя
            if (authentication != null && authentication.isAuthenticated()) {
                UserResponse currentUser = userService.getUserByEmail(authentication.getName());
                model.addAttribute("currentUserId", currentUser.getId());
                model.addAttribute("isOwnProfile", currentUser.getId().equals(userId));


                // ===== ЗАПИСЫВАЕМ ВИЗИТ (УПРОЩЕННО) =====
                ipAddressService.recordUserVisit(userId, currentUser.getId(), request);


                // Проверяем подписку только для чужих профилей
                if (!currentUser.getId().equals(userId)) {
                    boolean isSubscribed = subscriptionService.isSubscribed(currentUser.getId(), userId);
                    model.addAttribute("isSubscribed", isSubscribed);
                    log.debug("Статус подписки {} -> {}: {}", currentUser.getId(), userId, isSubscribed);
                } else {
                    model.addAttribute("isSubscribed", false);
                    log.debug("Собственный профиль - подписка не проверяется");
                }

                List<UserRecommendationDto> recommendations = recommendationService.getTopRecommendations(currentUser.getId());
                model.addAttribute("recommendations", recommendations);

            } else {
                log.debug("Пользователь не авторизован - визит не записывается");
                model.addAttribute("currentUserId", null);
                model.addAttribute("isSubscribed", false);
                model.addAttribute("isOwnProfile", false);
            }

            return "profile/profile";

        } catch (Exception e) {
            log.error("Ошибка при показе профиля {}: {}", userId, e.getMessage(), e);
            model.addAttribute("errorMessage", "Не удалось загрузить профиль пользователя");
            return "error/404";
        }
    }

    /**
     * AJAX загрузка постов для бесконечного скролла
     */
    @GetMapping("/{userId}/posts")
    @ResponseBody
    public ResponseEntity<PostListDto> getUserPostsAjax(@PathVariable Long userId,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "6") int size) {
        try {
            PostListDto userPosts = postService.getUserPosts(userId, page, size);
            return ResponseEntity.ok(userPosts);
        } catch (Exception e) {
            log.error("Ошибка AJAX загрузки постов пользователя {}: {}", userId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }


    @GetMapping("/{userId}/edit")
    public String showEditProfile(@PathVariable Long userId, Model model) {
        try {
            UserResponse user = userService.getUserById(userId);

            // Заполняем DTO текущими данными
            UpdateProfileDto dto = new UpdateProfileDto();
            dto.setFirstName(user.getFirstName());
            dto.setLastName(user.getLastName());
            dto.setCity(user.getCity());

            model.addAttribute("user", user);
            model.addAttribute("updateProfileDto", dto);

            model.addAttribute("maxFileSize", imageValidator.getMaxFileSizeMB());
            model.addAttribute("allowedFormats", imageValidator.getAllowedExtensions());
            model.addAttribute("validationRules", imageValidator.getValidationRulesDescription());

            return "profile/edit";
        } catch (RuntimeException e) {
            log.error("Ошибка при загрузке страницы редактирования профиля {}: {}", userId, e.getMessage());
            model.addAttribute("error", "Пользователь не найден");
            return "error/404";
        }
    }

    @PostMapping("/{userId}/edit")
    public String updateProfile(@PathVariable Long userId,
                                @Valid @ModelAttribute UpdateProfileDto updateDto,
                                BindingResult bindingResult,
                                Model model,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                return "redirect:/login";
            }

            UserResponse currentUser = userService.getUserByEmail(authentication.getName());

            if (!currentUser.getId().equals(userId)) {
                redirectAttributes.addFlashAttribute("errorMessage", "Недостаточно прав для редактирования");
                return "redirect:/profile/" + userId;
            }

            if (bindingResult.hasErrors()) {
                UserResponse user = userService.getUserById(userId);
                model.addAttribute("user", user);
                model.addAttribute("currentUserId", currentUser.getId());


                model.addAttribute("maxFileSize", imageValidator.getMaxFileSizeMB());
                model.addAttribute("allowedFormats", imageValidator.getAllowedExtensions());
                model.addAttribute("validationRules", imageValidator.getValidationRulesDescription());

                return "profile/edit";
            }

            userService.updateProfile(userId, updateDto);
            redirectAttributes.addFlashAttribute("successMessage", "Профиль успешно обновлен");

            return "redirect:/profile/" + userId;

        } catch (Exception e) {
            log.error("Ошибка при обновлении профиля {}: {}", userId, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Не удалось обновить профиль");
            return "redirect:/profile/" + userId + "/edit";
        }
    }


    @PostMapping("/{userId}/avatar")
    public String uploadAvatar(
            @PathVariable Long userId,
            @RequestParam("avatar") MultipartFile file,
            RedirectAttributes redirectAttributes) {


        // ✅ Минимальная проверка в контроллере
        if (file.isEmpty()) {
            log.warn("⚠️ Пустой файл аватара для пользователя {}", userId);
            redirectAttributes.addFlashAttribute("errorMessage", "Пожалуйста, выберите файл");
            return "redirect:/profile/" + userId + "/edit";
        }

        try {
            // ✅ Основная валидация и бизнес-логика В СЕРВИСЕ
            UserResponse updatedUser = userService.uploadAvatar(userId, file);

            redirectAttributes.addFlashAttribute("successMessage", "Аватар успешно обновлен!");
            return "redirect:/profile/" + userId;

        } catch (ImageValidationException e) {
            // Специфичные ошибки валидации изображений
            log.warn("❌ Ошибка валидации аватара для пользователя {}: {}", userId, e.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/profile/" + userId + "/edit";

        } catch (RuntimeException e) {
            // Общие ошибки
            log.error("❌ Ошибка при загрузке аватара для пользователя {}: {}", userId, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Ошибка при загрузке аватара. Попробуйте позже.");
            return "redirect:/profile/" + userId + "/edit";
        }
    }


    @PostMapping("/{userId}/background/upload")
    public String uploadBackgroundImage(
            @PathVariable Long userId,
            @RequestParam("backgroundFile") MultipartFile file,
            RedirectAttributes redirectAttributes) {

        log.info("📤 Загрузка фона для пользователя {}: {}", userId, file.getOriginalFilename());

        if (file.isEmpty()) {
            log.warn("⚠️ Пустой файл фона для пользователя {}", userId);
            redirectAttributes.addFlashAttribute("errorMessage", "Пожалуйста, выберите файл для загрузки");
            return "redirect:/profile/" + userId + "/edit";
        }

        try {
            userService.uploadBackgroundImage(userId, file);

            log.info("✅ Фон успешно загружен для пользователя {}", userId);
            redirectAttributes.addFlashAttribute("successMessage", "Фоновое изображение успешно загружено!");
            return "redirect:/profile/" + userId;

        } catch (ImageValidationException e) {
            // Специфичные ошибки валидации изображений
            log.warn("❌ Ошибка валидации фона для пользователя {}: {}", userId, e.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/profile/" + userId + "/edit";

        } catch (RuntimeException e) {
            log.error("❌ Ошибка при загрузке фона для пользователя {}: {}", userId, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Ошибка при загрузке фонового изображения: " + e.getMessage());
            return "redirect:/profile/" + userId + "/edit";
        }
    }

    /**
     * ✅ Удаление аватара
     */
    @PostMapping("/{userId}/avatar/delete")
    public String deleteAvatar(
            @PathVariable Long userId,
            RedirectAttributes redirectAttributes) {

        log.info("🗑️ Удаление аватара для пользователя {}", userId);

        try {
            userService.deleteAvatar(userId);

            log.info("✅ Аватар успешно удален для пользователя {}", userId);
            redirectAttributes.addFlashAttribute("successMessage", "Фотография профиля удалена!");
            return "redirect:/profile/" + userId;

        } catch (RuntimeException e) {
            log.error("❌ Ошибка при удалении аватара для пользователя {}: {}", userId, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Ошибка при удалении фотографии профиля");
            return "redirect:/profile/" + userId + "/edit";
        }
    }

    /**
     * ✅ Удаление фона
     */
    @PostMapping("/{userId}/background/delete")
    public String deleteBackground(
            @PathVariable Long userId,
            RedirectAttributes redirectAttributes) {

        log.info("🗑️ Удаление фона для пользователя {}", userId);

        try {
            userService.deleteBackgroundImage(userId);

            log.info("✅ Фон успешно удален для пользователя {}", userId);
            redirectAttributes.addFlashAttribute("successMessage", "Фоновое изображение удалено!");
            return "redirect:/profile/" + userId;

        } catch (RuntimeException e) {
            log.error("❌ Ошибка при удалении фона для пользователя {}: {}", userId, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Ошибка при удалении фонового изображения");
            return "redirect:/profile/" + userId + "/edit";
        }
    }

    // ========================================
    // ДОПОЛНИТЕЛЬНЫЕ УТИЛИТАРНЫЕ МЕТОДЫ
    // ========================================


    /**
     * Добавление настроек валидации в модель
     */
    private void addValidationAttributesToModel(Model model) {
        model.addAttribute("maxFileSize", imageValidator.getMaxFileSizeMB());
        model.addAttribute("allowedFormats", imageValidator.getAllowedExtensions());
        model.addAttribute("validationRules", imageValidator.getValidationRulesDescription());
    }


}