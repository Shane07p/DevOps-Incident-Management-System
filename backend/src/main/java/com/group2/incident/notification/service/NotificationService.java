package com.group2.incident.notification.service;

import com.group2.incident.auth.entity.AppUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class NotificationService {

    public void notifyUser(AppUser recipient, String message) {
        log.info("Notification sent to {}: {}", recipient != null ? recipient.getUsername() : "null", message);
    }
}
