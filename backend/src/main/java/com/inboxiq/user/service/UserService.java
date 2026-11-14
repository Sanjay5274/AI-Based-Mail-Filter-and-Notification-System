package com.inboxiq.user.service;

import com.inboxiq.common.exception.ResourceNotFoundException;
import com.inboxiq.user.dto.UserProfileResponse;
import com.inboxiq.user.entity.User;
import com.inboxiq.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserProfileResponse getUserProfile(UUID userId) {
        User user = findById(userId);
        return UserProfileResponse.fromEntity(user);
    }

    public User findById(UUID userId) {
        return userRepository.findByIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
    }
}

