package com.bit.notes.controller;

import com.bit.notes.entity.AppUser;
import com.bit.notes.repository.AppUserRepository;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    public static final String AUTH_USER_ID_KEY = "AUTH_USER_ID";
    public static final String AUTH_EMAIL_KEY = "AUTH_EMAIL";
    public static final String AUTH_NAME_KEY = "AUTH_NAME";
    public static final String AUTH_IS_ADMIN_KEY = "AUTH_IS_ADMIN";

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 72;

    private final AppUserRepository appUserRepository;
    private final String preferredAdminEmail;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(
            AppUserRepository appUserRepository,
            @Value("${app.admin.email:}") String preferredAdminEmail) {
        this.appUserRepository = appUserRepository;
        this.preferredAdminEmail = normalizeEmail(preferredAdminEmail);
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> payload) {
        String name = payload == null ? "" : payload.getOrDefault("name", "");
        String email = payload == null ? "" : payload.getOrDefault("email", "");
        String password = payload == null ? "" : payload.getOrDefault("password", "");

        String normalizedName = normalizeName(name);
        String normalizedEmail = normalizeEmail(email);
        String normalizedPassword = normalizePassword(password);

        if (normalizedName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Name is required."));
        }
        if (!isValidEmail(normalizedEmail)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Please enter a valid email."));
        }
        if (!isValidPassword(normalizedPassword)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Password must be 8 to 72 characters."));
        }

        if (appUserRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("message", "Email already registered. Please login."));
        }

        AppUser appUser = new AppUser();
        appUser.setName(normalizedName);
        appUser.setEmail(normalizedEmail);
        appUser.setPasswordHash(passwordEncoder.encode(normalizedPassword));
        AppUser savedUser = appUserRepository.save(appUser);
        savedUser = applyAdminPolicy(savedUser);
        return ResponseEntity.status(201).body(buildUserResponse("Registration successful. Please login.", savedUser));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> payload, HttpSession session) {
        String email = payload == null ? "" : payload.getOrDefault("email", "");
        String password = payload == null ? "" : payload.getOrDefault("password", "");

        String normalizedEmail = normalizeEmail(email);
        String normalizedPassword = normalizePassword(password);

        if (!isValidEmail(normalizedEmail)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Please enter a valid email."));
        }
        if (!isValidPassword(normalizedPassword)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Password must be 8 to 72 characters."));
        }

        Optional<AppUser> existingUser = appUserRepository.findByEmailIgnoreCase(normalizedEmail);
        if (existingUser.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message", "Account not found. Please register first."));
        }

        AppUser appUser = existingUser.get();
        if (appUser.getPasswordHash() != null && !appUser.getPasswordHash().isBlank()) {
            if (!passwordEncoder.matches(normalizedPassword, appUser.getPasswordHash())) {
                return ResponseEntity.status(401).body(Map.of("message", "Invalid email or password."));
            }
        } else {
            // Backward compatibility for accounts created before password support.
            appUser.setPasswordHash(passwordEncoder.encode(normalizedPassword));
        }

        String providedName = normalizeName(payload == null ? "" : payload.getOrDefault("name", ""));
        if (!providedName.isBlank()) {
            appUser.setName(providedName);
        }
        AppUser savedUser = appUserRepository.save(appUser);
        savedUser = applyAdminPolicy(savedUser);
        bindSession(session, savedUser);
        return ResponseEntity.ok(buildUserResponse("Login successful.", savedUser));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        Optional<AppUser> userOptional = resolveUserFromSession(session);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        }

        AppUser user = applyAdminPolicy(userOptional.get());
        bindSession(session, user);
        return ResponseEntity.ok(buildUserResponse("Authenticated", user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("message", "Logged out."));
    }

    private Optional<AppUser> resolveUserFromSession(HttpSession session) {
        if (session == null) {
            return Optional.empty();
        }

        Object userIdValue = session.getAttribute(AUTH_USER_ID_KEY);
        if (userIdValue instanceof Number userIdNumber) {
            long userId = userIdNumber.longValue();
            Optional<AppUser> byId = appUserRepository.findById(userId);
            if (byId.isPresent()) {
                return byId;
            }
        }

        String email = String.valueOf(session.getAttribute(AUTH_EMAIL_KEY));
        if (isValidEmail(normalizeEmail(email))) {
            return appUserRepository.findByEmailIgnoreCase(normalizeEmail(email));
        }

        return Optional.empty();
    }

    private Map<String, Object> buildUserResponse(String message, AppUser appUser) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("id", appUser.getId());
        body.put("name", appUser.getName());
        body.put("email", appUser.getEmail());
        body.put("isAdmin", appUser.isAdmin());
        return body;
    }

    private void bindSession(HttpSession session, AppUser appUser) {
        session.setAttribute(AUTH_USER_ID_KEY, appUser.getId());
        session.setAttribute(AUTH_EMAIL_KEY, appUser.getEmail());
        session.setAttribute(AUTH_NAME_KEY, appUser.getName());
        session.setAttribute(AUTH_IS_ADMIN_KEY, appUser.isAdmin());
    }

    private AppUser applyAdminPolicy(AppUser appUser) {
        String userEmail = normalizeEmail(appUser.getEmail());

        if (!preferredAdminEmail.isBlank()) {
            if (preferredAdminEmail.equals(userEmail)) {
                return promoteToUniqueAdmin(appUser);
            }

            if (appUser.isAdmin()) {
                appUser.setAdmin(false);
                return appUserRepository.save(appUser);
            }
            return appUser;
        }

        if (!appUserRepository.existsByIsAdminTrue()) {
            return promoteToUniqueAdmin(appUser);
        }

        return appUser;
    }

    private AppUser promoteToUniqueAdmin(AppUser appUser) {
        List<AppUser> admins = appUserRepository.findByIsAdminTrue();
        boolean otherAdminUpdated = false;
        for (AppUser admin : admins) {
            if (!admin.getId().equals(appUser.getId())) {
                admin.setAdmin(false);
                otherAdminUpdated = true;
            }
        }

        if (otherAdminUpdated) {
            appUserRepository.saveAll(admins);
        }

        if (!appUser.isAdmin()) {
            appUser.setAdmin(true);
            return appUserRepository.save(appUser);
        }

        return appUser;
    }

    private static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("\\s+", " ");
    }

    private static boolean isValidEmail(String email) {
        return EMAIL_PATTERN.matcher(normalizeEmail(email)).matches();
    }

    private static boolean isValidPassword(String password) {
        int length = normalizePassword(password).length();
        return length >= MIN_PASSWORD_LENGTH && length <= MAX_PASSWORD_LENGTH;
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        return email.trim().toLowerCase();
    }

    private static String normalizePassword(String password) {
        if (password == null) {
            return "";
        }
        return password.trim();
    }
}
