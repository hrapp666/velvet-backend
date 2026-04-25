package com.velvet.backend.repository;

import com.velvet.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    Optional<User> findByAppleUserId(String appleUserId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    /** 按昵称/账号模糊搜索 */
    @Query("SELECT u FROM User u WHERE " +
           "LOWER(u.nickname) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "OR LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "ORDER BY u.followersCount DESC, u.id DESC")
    Page<User> searchByQuery(@Param("q") String q, Pageable pageable);
}
