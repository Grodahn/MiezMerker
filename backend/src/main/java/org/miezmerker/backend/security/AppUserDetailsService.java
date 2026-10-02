package org.miezmerker.backend.security;

import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.repo.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {
    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = AppUser.normalizeEmail(username);
        AppUser user = users.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("unknown user"));
        return new AppUserDetails(user.getId(), user.getEmail(), user.getPasswordHash(), user.getStatus());
    }
}
