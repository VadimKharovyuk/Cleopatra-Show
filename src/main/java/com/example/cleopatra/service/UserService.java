package com.example.cleopatra.service;

import com.example.cleopatra.dto.ChatMessage.UserBriefDto;
import com.example.cleopatra.dto.user.RegisterDto;
import com.example.cleopatra.dto.user.UpdateProfileDto;
import com.example.cleopatra.dto.user.UserResponse;
import com.example.cleopatra.model.User;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface UserService {

    UserResponse createUser(RegisterDto  registerDto);

    // Только загрузка/обновление аватара
 UserResponse uploadAvatar(Long userId, MultipartFile file) ;



  UserResponse updateProfile(Long userId, UpdateProfileDto profileDto);


    UserResponse getUserById(Long userId);

    void validateUserExists(Long userId);


    boolean userExists(Long userId);


    UserResponse deleteAvatar(Long userId);

    UserResponse uploadBackgroundImage(Long userId, MultipartFile file);
    UserResponse deleteBackgroundImage(Long userId);

    UserResponse getUserByEmail(String userEmail);

    User getCurrentUserEntity();
    User getCurrentUserEntity(Authentication authentication);


    void updateOnlineStatus(Long userId, boolean b);


    boolean isUserOnline(Long otherUserId);

    String getUserStatusText(Long otherUserId);


    UserBriefDto convertToUserBriefDto(User user);


    Long getUserIdByEmail(String email);

    void setUserOnline(Long userId, boolean isOnline);
    List<UserResponse> getOnlineUsers();



    void updateLastActivity(Long userId);

  // Метод для обновления настроек уведомлений
void updateNotificationSettings(Long userId, Boolean receiveVisitNotifications);



}
