package com.siva.storybot.repository;

import com.siva.storybot.entity.TelegramUser;
import com.siva.storybot.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TelegramUserRepository extends JpaRepository<TelegramUser, Long> {

    Optional<TelegramUser> findByTelegramId(Long telegramId);

    Page<TelegramUser> findAll(Pageable pageable);

    Optional<TelegramUser> findByUsername(String username);

    Page<TelegramUser> findByRoleNotOrderByLastActiveAtDesc(UserRole role, Pageable pageable);

}