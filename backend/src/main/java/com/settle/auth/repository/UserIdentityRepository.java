package com.settle.auth.repository;

import com.settle.auth.entity.OAuthProvider;
import com.settle.auth.entity.UserIdentity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

	Optional<UserIdentity> findByProviderAndSubject(OAuthProvider provider, String subject);
}
