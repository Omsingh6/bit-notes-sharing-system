package com.bit.notes.controller;

import com.bit.notes.entity.AppUser;
import com.bit.notes.entity.Notes;
import com.bit.notes.repository.AppUserRepository;
import com.bit.notes.repository.NotesRepository;
import jakarta.servlet.http.HttpSession;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/notes")
public class NotesController {

    private final NotesRepository notesRepository;
    private final AppUserRepository appUserRepository;
    private final Path uploadRoot;
    private final long maxUploadBytes;

    public NotesController(
            NotesRepository notesRepository,
            AppUserRepository appUserRepository,
            @Value("${app.upload.dir:uploads}") String uploadDir,
            @Value("${app.upload.max-file-size-bytes:10485760}") long maxUploadBytes) {
        this.notesRepository = notesRepository;
        this.appUserRepository = appUserRepository;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.maxUploadBytes = maxUploadBytes > 0 ? maxUploadBytes : 10 * 1024 * 1024;
    }

    @GetMapping("/test")
    public String test() {
        return "Notes API working";
    }

    @PostMapping("/save")
    public ResponseEntity<?> saveNote(@RequestBody SaveNoteRequest payload, HttpSession session) {
        Optional<AppUser> userOptional = getCurrentUser(session);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        AppUser currentUser = userOptional.get();

        if (payload == null) {
            return ResponseEntity.badRequest().body("Request payload is required.");
        }

        String safeTitle = normalizeText(payload.getTitle());
        String safeSubject = normalizeText(payload.getSubject());
        String safeYear = normalizeText(payload.getAcademicYear());
        String safeSemester = normalizeText(payload.getSemester());
        String safeContent = normalizeText(payload.getContent());

        if (safeTitle.isBlank() || safeSubject.isBlank() || safeYear.isBlank() || safeSemester.isBlank()) {
            return ResponseEntity.badRequest().body("Title, Subject, Academic Year, and Semester are required.");
        }

        Notes note = new Notes();
        note.setTitle(safeTitle);
        note.setSubject(safeSubject);
        note.setAcademicYear(safeYear);
        note.setSemester(safeSemester);
        note.setContent(safeContent);

        // File metadata must only be set by the upload endpoint.
        note.setFileName(null);
        note.setFilePath(null);
        note.setOwner(currentUser);
        Notes saved = notesRepository.save(note);
        return ResponseEntity.ok(buildNoteResponse(saved));
    }

    @GetMapping("/all")
    public ResponseEntity<?> getAllNotes(HttpSession session) {
        Optional<AppUser> userOptional = getCurrentUser(session);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        AppUser currentUser = userOptional.get();

        List<Notes> notes = currentUser.isAdmin()
                ? notesRepository.findAllByOrderByIdDesc()
                : notesRepository.findByOwner_IdOrderByIdDesc(currentUser.getId());

        return ResponseEntity.ok(notes.stream().map(this::buildNoteResponse).toList());
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(
            @RequestParam("title") String title,
            @RequestParam("subject") String subject,
            @RequestParam("academicYear") String academicYear,
            @RequestParam("semester") String semester,
            @RequestParam("file") MultipartFile file,
            HttpSession session) {

        Optional<AppUser> userOptional = getCurrentUser(session);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        AppUser currentUser = userOptional.get();

        String safeTitle = normalizeText(title);
        String safeSubject = normalizeText(subject);
        String safeYear = normalizeText(academicYear);
        String safeSemester = normalizeText(semester);

        if (safeTitle.isBlank() || safeSubject.isBlank() || safeYear.isBlank() || safeSemester.isBlank()) {
            return ResponseEntity.badRequest().body("Title, Subject, Academic Year, and Semester are required.");
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("Please choose a PDF file.");
        }

        String originalName = file.getOriginalFilename() == null ? "notes.pdf" : file.getOriginalFilename();
        if (file.getSize() > maxUploadBytes) {
            return ResponseEntity.status(413).body("File too large.");
        }

        if (!isValidPdf(file, originalName)) {
            return ResponseEntity.badRequest().body("Invalid file. Please upload a valid PDF.");
        }

        boolean isDuplicate = notesRepository.existsByAcademicYearIgnoreCaseAndSemesterIgnoreCaseAndSubjectIgnoreCaseAndTitleIgnoreCaseAndOwner_Id(
                safeYear,
                safeSemester,
                safeSubject,
                safeTitle,
                currentUser.getId()
        );

        if (isDuplicate) {
            return ResponseEntity.status(409).body("Duplicate entry: this title already exists for selected year, semester, and subject.");
        }

        String safeName = UUID.randomUUID() + "-" + originalName.replaceAll("[^a-zA-Z0-9._-]", "_");

        try {
            Files.createDirectories(uploadRoot);
            Path target = uploadRoot.resolve(safeName).normalize();
            if (!target.startsWith(uploadRoot)) {
                return ResponseEntity.internalServerError().body("Upload path validation failed.");
            }
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            Notes note = new Notes();
            note.setTitle(safeTitle);
            note.setSubject(safeSubject);
            note.setAcademicYear(safeYear);
            note.setSemester(safeSemester);
            note.setFileName(originalName);
            note.setFilePath(target.toString());
            note.setOwner(currentUser);

            notesRepository.save(note);
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("Could not store file.");
        }

        return ResponseEntity.ok("File uploaded successfully");
    }

    @GetMapping("/file/{id}")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id, HttpSession session) {
        Optional<AppUser> userOptional = getCurrentUser(session);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        AppUser currentUser = userOptional.get();

        Optional<Notes> noteOptional = notesRepository.findById(id);
        if (noteOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Notes note = noteOptional.get();
        if (!canAccessNote(note, currentUser)) {
            return ResponseEntity.status(403).build();
        }

        if (note.getFilePath() == null || note.getFilePath().isBlank()) {
            return ResponseEntity.notFound().build();
        }

        Path filePath;
        try {
            filePath = Paths.get(note.getFilePath()).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return ResponseEntity.notFound().build();
        }

        if (!filePath.startsWith(uploadRoot)) {
            return ResponseEntity.status(403).build();
        }

        Resource resource;
        try {
            resource = new UrlResource(filePath.toUri());
        } catch (Exception ex) {
            return ResponseEntity.notFound().build();
        }

        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + note.getFileName() + "\"")
                .body(resource);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteNote(@PathVariable Long id, HttpSession session) {
        ResponseEntity<String> unauthorized = unauthorizedIfNeeded(session);
        if (unauthorized != null) {
            return unauthorized;
        }
        ResponseEntity<String> forbidden = forbiddenIfNotAdmin(session);
        if (forbidden != null) {
            return forbidden;
        }

        Optional<Notes> noteOptional = notesRepository.findById(id);
        if (noteOptional.isEmpty()) {
            return ResponseEntity.status(404).body("Note not found.");
        }

        Notes note = noteOptional.get();
        String filePath = note.getFilePath();

        if (filePath != null && !filePath.isBlank()) {
            try {
                Path normalized = Paths.get(filePath).toAbsolutePath().normalize();
                if (!normalized.startsWith(uploadRoot)) {
                    return ResponseEntity.badRequest().body("Invalid note file path.");
                }
                Files.deleteIfExists(normalized);
            } catch (Exception ex) {
                return ResponseEntity.internalServerError().body("Could not delete file from disk.");
            }
        }

        notesRepository.deleteById(id);
        return ResponseEntity.ok("Note deleted successfully.");
    }

    private ResponseEntity<String> unauthorizedIfNeeded(HttpSession session) {
        if (getCurrentUser(session).isEmpty()) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return null;
    }

    private ResponseEntity<String> forbiddenIfNotAdmin(HttpSession session) {
        Optional<AppUser> userOptional = getCurrentUser(session);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        if (!userOptional.get().isAdmin()) {
            return ResponseEntity.status(403).body("Only admin can delete notes.");
        }
        return null;
    }

    private Optional<AppUser> getCurrentUser(HttpSession session) {
        if (session == null) {
            return Optional.empty();
        }

        Object userIdValue = session.getAttribute(AuthController.AUTH_USER_ID_KEY);
        if (!(userIdValue instanceof Number userIdNumber)) {
            return Optional.empty();
        }

        long userId = userIdNumber.longValue();
        return appUserRepository.findById(userId);
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static boolean isValidPdf(MultipartFile file, String originalName) {
        String fileName = originalName == null ? "" : originalName.trim().toLowerCase();
        if (!fileName.endsWith(".pdf")) {
            return false;
        }

        try (InputStream in = file.getInputStream()) {
            byte[] signature = in.readNBytes(5);
            return signature.length == 5
                    && signature[0] == '%'
                    && signature[1] == 'P'
                    && signature[2] == 'D'
                    && signature[3] == 'F'
                    && signature[4] == '-';
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean canAccessNote(Notes note, AppUser currentUser) {
        if (currentUser.isAdmin()) {
            return true;
        }
        AppUser owner = note.getOwner();
        return owner != null && owner.getId() != null && owner.getId().equals(currentUser.getId());
    }

    private Map<String, Object> buildNoteResponse(Notes note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", note.getId());
        body.put("title", note.getTitle());
        body.put("content", note.getContent());
        body.put("academicYear", note.getAcademicYear());
        body.put("semester", note.getSemester());
        body.put("subject", note.getSubject());
        body.put("fileName", note.getFileName());
        AppUser owner = note.getOwner();
        body.put("ownerId", owner == null ? null : owner.getId());
        body.put("ownerName", owner == null ? null : owner.getName());
        return body;
    }

    public static class SaveNoteRequest {
        private String title;
        private String content;
        private String academicYear;
        private String semester;
        private String subject;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getAcademicYear() {
            return academicYear;
        }

        public void setAcademicYear(String academicYear) {
            this.academicYear = academicYear;
        }

        public String getSemester() {
            return semester;
        }

        public void setSemester(String semester) {
            this.semester = semester;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }
    }
}
