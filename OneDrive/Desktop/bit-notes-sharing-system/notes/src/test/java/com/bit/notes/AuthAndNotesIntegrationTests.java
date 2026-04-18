package com.bit.notes;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bit.notes.entity.Notes;
import com.bit.notes.repository.NotesRepository;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AuthAndNotesIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotesRepository notesRepository;

    @AfterEach
    void cleanupUploadArtifacts() throws Exception {
        Path uploadRoot = Path.of("target", "test-uploads").toAbsolutePath().normalize();
        if (!Files.exists(uploadRoot)) {
            return;
        }

        try (var stream = Files.walk(uploadRoot)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Test
    void loginRejectsShortPassword() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String payload = """
                {
                  "name":"User",
                  "email":"%s",
                  "password":"12345"
                }
                """.formatted(email);

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginRequiresCorrectPasswordForExistingAccount() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String registerPayload = """
                {
                  "name":"User",
                  "email":"%s",
                  "password":"StrongPass123"
                }
                """.formatted(email);
        String okPayload = """
                {
                  "email":"%s",
                  "password":"StrongPass123"
                }
                """.formatted(email);
        String badPayload = """
                {
                  "email":"%s",
                  "password":"WrongPass123"
                }
                """.formatted(email);

        mockMvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(registerPayload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(okPayload))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(badPayload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithoutRegistrationReturnsNotFound() throws Exception {
        String payload = """
                {
                  "email":"missing-%s@example.com",
                  "password":"StrongPass123"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound());
    }

    @Test
    void notesAllRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/notes/all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadRejectsNonPdfContent() throws Exception {
        MockHttpSession session = loginAndGetSession();
        MockMultipartFile textFile = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "not a pdf".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/notes/upload")
                        .file(textFile)
                        .param("title", "CN Notes")
                        .param("subject", "Computer Networks")
                        .param("academicYear", "2nd Year")
                        .param("semester", "Semester 3")
                        .session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void saveEndpointDoesNotPersistFileMetadataFromClientPayload() throws Exception {
        MockHttpSession session = loginAndGetSession();
        String payload = """
                {
                  "title":"DSA Notes",
                  "content":"content",
                  "academicYear":"2nd Year",
                  "semester":"Semester 3",
                  "subject":"Data Structure",
                  "fileName":"forced.pdf",
                  "filePath":"C:/Windows/win.ini"
                }
                """;

        mockMvc.perform(post("/notes/save")
                        .contentType(APPLICATION_JSON)
                        .content(payload)
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filePath").doesNotExist())
                .andExpect(jsonPath("$.fileName", nullValue()))
                .andExpect(jsonPath("$.ownerId").exists());

        Notes saved = notesRepository.findAll().stream()
                .filter(note -> "DSA Notes".equals(note.getTitle()))
                .findFirst()
                .orElseThrow();

        Assertions.assertNull(saved.getFilePath());
        Assertions.assertNull(saved.getFileName());
    }

    @Test
    void fileEndpointBlocksPathsOutsideUploadRoot() throws Exception {
        MockHttpSession session = loginAndGetSession();

        Notes note = new Notes();
        note.setTitle("Unsafe");
        note.setSubject("Security");
        note.setAcademicYear("3rd Year");
        note.setSemester("Semester 5");
        note.setFileName("unsafe.pdf");
        note.setFilePath("C:/Windows/win.ini");
        Notes saved = notesRepository.save(note);

        mockMvc.perform(get("/notes/file/{id}", saved.getId()).session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void notesAreSeparatedByLoggedInUser() throws Exception {
        MockHttpSession user1Session = loginAndGetSession();
        MockHttpSession user2Session = loginAndGetSession();

        String user1NotePayload = """
                {
                  "title":"User 1 Note",
                  "content":"content",
                  "academicYear":"2nd Year",
                  "semester":"Semester 3",
                  "subject":"Data Structure"
                }
                """;

        String user2NotePayload = """
                {
                  "title":"User 2 Note",
                  "content":"content",
                  "academicYear":"2nd Year",
                  "semester":"Semester 3",
                  "subject":"Data Structure"
                }
                """;

        mockMvc.perform(post("/notes/save")
                        .contentType(APPLICATION_JSON)
                        .content(user1NotePayload)
                        .session(user1Session))
                .andExpect(status().isOk());

        mockMvc.perform(post("/notes/save")
                        .contentType(APPLICATION_JSON)
                        .content(user2NotePayload)
                        .session(user2Session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/notes/all").session(user2Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("User 2 Note"));
    }

    private MockHttpSession loginAndGetSession() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String registerPayload = """
                {
                  "name":"Test User",
                  "email":"%s",
                  "password":"StrongPass123"
                }
                """.formatted(email);
        String loginPayload = """
                {
                  "email":"%s",
                  "password":"StrongPass123"
                }
                """.formatted(email);

        mockMvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(registerPayload))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andReturn();

        HttpSession session = result.getRequest().getSession(false);
        return (MockHttpSession) session;
    }
}
