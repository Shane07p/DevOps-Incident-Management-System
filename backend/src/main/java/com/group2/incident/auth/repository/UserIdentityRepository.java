package com.group2.incident.auth.repository;

import com.group2.incident.auth.entity.OAuthProvider;
import com.group2.incident.auth.entity.UserIdentity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

	Optional<UserIdentity> findByProviderAndSubject(OAuthProvider provider, String subject);
}
