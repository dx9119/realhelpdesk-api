package com.ukhanov.realhelpdesk.core.security.user;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserStatus;

import lombok.RequiredArgsConstructor;

/**
 * Реализация {@link UserDetails} для текущего пользователя. Аутентификацию обеспечивает {@code JwtAuthenticationToken}
 * из Spring Security OAuth2, поэтому этот класс не участвует в цепочке — он оставлен как совместимость.
 */
@RequiredArgsConstructor
public class SecurityUser implements UserDetails {
    private final UserModel user;

    public UserModel getOriginalUser() {
        return this.user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> userRoles = new HashSet<>();
        String userRole = String.valueOf(user.getUserRole());
        userRoles.add(new SimpleGrantedAuthority(userRole));

        return userRoles;
    }

    public String getId() {
        return user.getId().toString();
    }

    public String getRule() {
        return String.valueOf(user.getUserRole());
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return user.getUserStatus() == UserStatus.ACTIVE;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.getUserStatus() == UserStatus.ACTIVE;
    }
}
