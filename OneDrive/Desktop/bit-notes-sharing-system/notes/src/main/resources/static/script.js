let API_BASE = "";
let apiReady = false;
let AUTH_BASE = "";
let authReady = false;
const LOGIN_PAGE = "index.html";
const SAVED_EMAIL_KEY = "bit_notes_saved_email";

const YEARS = ["", "1st Year", "2nd Year", "3rd Year", "4th Year"];
const SEMESTERS = ["", "Semester 1", "Semester 2", "Semester 3", "Semester 4", "Semester 5", "Semester 6", "Semester 7", "Semester 8"];
const SEMESTERS_BY_YEAR = {
    "1st Year": ["", "Semester 1", "Semester 2"],
    "2nd Year": ["", "Semester 3", "Semester 4"],
    "3rd Year": ["", "Semester 5", "Semester 6"],
    "4th Year": ["", "Semester 7", "Semester 8"]
};
const DEFAULT_SUBJECTS = ["", "Mathematics", "Physics", "Chemistry", "Computer Networks", "DBMS", "Operating Systems", "Java", "Python"];
const SUBJECTS_BY_SESSION = {
    "1st Year|Semester 1": [
        "",
        "Mathematics-I",
        "Physics",
        "Graphics",
        "Basic Electrical and Electronics",
        "Basic Civil and Mechanical Engineering",
        "IKS",
        "Design Thinking"
    ],
    "1st Year|Semester 2": [
        "",
        "Mathematics-II",
        "Chemistry",
        "Mechanics",
        "Programming for Problem Solving",
        "Communication Skill"
    ],
    "2nd Year|Semester 3": [
        "",
        "Java",
        "Mathematics-III",
        "Data Structure",
        "Discrete Mathematics",
        "Object-Oriented Programming",
        "Digital Electronics",
        "Universal Human Values-II",
        "Soft Skill Development"
    ],
    "2nd Year|Semester 4": [
        "",
        "Design and Analysis of Algorithms",
        "Computer Architecture and Organisation",
        "Probability and Statistics",
        "Python Programming",
        "Life of Dr. Babasaheb Ambedkar",
        "Hindi",
        "Marathi",
        "Sanskrit"
    ]
};

let allNotes = [];
let currentUserIsAdmin = false;

async function ensureAuthBase() {
    if (authReady) {
        return true;
    }

    const protocol = window.location.protocol === "https:" ? "https:" : "http:";
    const host = window.location.hostname || "localhost";
    const candidates = [
        `${window.location.origin}/auth`,
        `${protocol}//${host}:8088/auth`,
        `${protocol}//localhost:8088/auth`,
        `${protocol}//127.0.0.1:8088/auth`,
        `${protocol}//${host}:8080/auth`,
        `${protocol}//localhost:8080/auth`,
        `${protocol}//127.0.0.1:8080/auth`,
        `${protocol}//${host}:8081/auth`,
        `${protocol}//localhost:8081/auth`,
        `${protocol}//127.0.0.1:8081/auth`
    ];

    for (const candidate of candidates) {
        try {
            const response = await fetch(`${candidate}/me`, {
                method: "GET",
                credentials: "include"
            });
            if (response.status === 404) {
                continue;
            }

            AUTH_BASE = candidate;
            authReady = true;
            return true;
        } catch (error) {
            // Try next candidate.
        }
    }

    return false;
}

function authUrl(path) {
    return `${AUTH_BASE}${path}`;
}

async function ensureApiBase() {
    if (apiReady) {
        return true;
    }

    const protocol = window.location.protocol === "https:" ? "https:" : "http:";
    const host = window.location.hostname || "localhost";
    const candidates = [
        `${window.location.origin}/notes`,
        `${protocol}//${host}:8088/notes`,
        `${protocol}//localhost:8088/notes`,
        `${protocol}//127.0.0.1:8088/notes`,
        `${protocol}//${host}:8080/notes`,
        `${protocol}//localhost:8080/notes`,
        `${protocol}//127.0.0.1:8080/notes`,
        `${protocol}//${host}:8081/notes`,
        `${protocol}//localhost:8081/notes`,
        `${protocol}//127.0.0.1:8081/notes`
    ];

    for (const candidate of candidates) {
        try {
            const res = await fetch(`${candidate}/test`, {
                method: "GET",
                credentials: "include"
            });
            if (res.ok) {
                API_BASE = candidate;
                apiReady = true;
                return true;
            }
        } catch (error) {
            // Try next candidate.
        }
    }

    return false;
}

async function checkSessionAndBindUser() {
    try {
        const authConnected = await ensureAuthBase();
        if (!authConnected) {
            window.location.href = LOGIN_PAGE;
            return false;
        }

        const response = await fetch(authUrl("/me"), {
            method: "GET",
            credentials: "include"
        });
        if (response.status === 401) {
            window.location.href = LOGIN_PAGE;
            return false;
        }
        if (!response.ok) {
            throw new Error("Session check failed.");
        }

        const data = await response.json();
        currentUserIsAdmin = Boolean(data.isAdmin);
        const userEmailElement = document.getElementById("userEmail");
        if (userEmailElement) {
            const name = data.name || "";
            const email = data.email || "";
            const userText = name && email ? `${name} (${email})` : (email || name);
            userEmailElement.textContent = currentUserIsAdmin ? `${userText} | Admin` : userText;
        }
        if (data.email) {
            localStorage.setItem(SAVED_EMAIL_KEY, data.email);
        }
        return true;
    } catch (error) {
        window.location.href = LOGIN_PAGE;
        return false;
    }
}

async function logout() {
    try {
        const authConnected = await ensureAuthBase();
        if (authConnected) {
            await fetch(authUrl("/logout"), {
                method: "POST",
                credentials: "include"
            });
        }
    } catch (error) {
        // Ignore and redirect anyway.
    } finally {
        localStorage.removeItem(SAVED_EMAIL_KEY);
        window.location.href = LOGIN_PAGE;
    }
}

function setGlobalStatus(message, type = "info") {
    const statusElement = document.getElementById("globalStatus");
    if (!statusElement) {
        return;
    }

    if (!message) {
        statusElement.textContent = "";
        statusElement.className = "status-banner hidden";
        return;
    }

    statusElement.textContent = message;
    statusElement.className = `status-banner ${type}`;
}

function fillSelect(id, options, placeholder) {
    const select = document.getElementById(id);
    select.innerHTML = options
        .map((value) => {
            const label = value || placeholder;
            return `<option value="${escapeHtml(value)}">${escapeHtml(label)}</option>`;
        })
        .join("");
}

function getSemestersForYear(year) {
    if (!year) {
        return [""];
    }
    return SEMESTERS_BY_YEAR[year] || [""];
}

function syncSemesterSelect(yearSelectId, semesterSelectId) {
    const year = document.getElementById(yearSelectId).value;
    const semesterSelect = document.getElementById(semesterSelectId);
    const previousSemester = semesterSelect.value;
    const allowedSemesters = getSemestersForYear(year);

    fillSelect(semesterSelectId, allowedSemesters, "Select semester");

    if (allowedSemesters.includes(previousSemester)) {
        semesterSelect.value = previousSemester;
    }
}

function setSelectEnabled(id, enabled) {
    const select = document.getElementById(id);
    select.disabled = !enabled;
}

function setupSelects() {
    fillSelect("uploadYear", YEARS, "Select year");
    fillSelect("uploadSemester", getSemestersForYear(""), "Select semester");
    fillSelect("uploadSubject", DEFAULT_SUBJECTS, "Select subject");

    fillSelect("filterYear", YEARS, "Select year");
    fillSelect("filterSemester", getSemestersForYear(""), "Select semester");
    fillSelect("filterSubject", DEFAULT_SUBJECTS, "Select subject");

    document.getElementById("uploadYear").addEventListener("change", updateUploadSessionSelection);
    document.getElementById("uploadSemester").addEventListener("change", updateUploadSubjects);
    document.getElementById("filterYear").addEventListener("change", updateFilterSessionSelection);
    document.getElementById("filterSemester").addEventListener("change", updateFilterSubjects);
}

function getSubjectsFor(year, semester) {
    const key = `${year}|${semester}`;
    return SUBJECTS_BY_SESSION[key] || DEFAULT_SUBJECTS;
}

function updateUploadSessionSelection() {
    const year = document.getElementById("uploadYear").value;
    const hasYear = Boolean(year);

    syncSemesterSelect("uploadYear", "uploadSemester");
    setSelectEnabled("uploadSemester", hasYear);
    updateUploadSubjects();
}

function updateFilterSessionSelection() {
    const year = document.getElementById("filterYear").value;
    const hasYear = Boolean(year);

    syncSemesterSelect("filterYear", "filterSemester");
    setSelectEnabled("filterSemester", hasYear);
    updateFilterSubjects();
}

function updateUploadSubjects() {
    const year = document.getElementById("uploadYear").value;
    const semester = document.getElementById("uploadSemester").value;
    const canSelectSubject = Boolean(year && semester);

    if (!canSelectSubject) {
        fillSelect("uploadSubject", [""], year ? "Select semester first" : "Select year first");
        setSelectEnabled("uploadSubject", false);
        return;
    }

    fillSelect("uploadSubject", getSubjectsFor(year, semester), "Select subject");
    setSelectEnabled("uploadSubject", true);
}

function updateFilterSubjects() {
    const year = document.getElementById("filterYear").value;
    const semester = document.getElementById("filterSemester").value;
    const canSelectSubject = Boolean(year && semester);

    if (!canSelectSubject) {
        fillSelect("filterSubject", [""], year ? "Select semester first" : "Select year first");
        setSelectEnabled("filterSubject", false);
        applyFiltersAndRender();
        return;
    }

    fillSelect("filterSubject", getSubjectsFor(year, semester), "Select subject");
    setSelectEnabled("filterSubject", true);
    applyFiltersAndRender();
}

async function uploadPdfNote() {
    const connected = await ensureApiBase();
    if (!connected) {
        setGlobalStatus("Backend not reachable. Start the Spring Boot app and refresh.", "error");
        return;
    }

    const title = document.getElementById("pdfTitle").value.trim();
    const subject = document.getElementById("uploadSubject").value;
    const academicYear = document.getElementById("uploadYear").value;
    const semester = document.getElementById("uploadSemester").value;
    const fileInput = document.getElementById("pdfFile");
    const file = fileInput.files[0];

    if (!academicYear || !semester) {
        setGlobalStatus("Please select academic year and semester.", "error");
        return;
    }

    if (!title || !subject || !file) {
        setGlobalStatus("Please fill title, subject, and choose a PDF file.", "error");
        return;
    }

    if (!file.name.toLowerCase().endsWith(".pdf")) {
        setGlobalStatus("Only PDF files are allowed.", "error");
        return;
    }

    const formData = new FormData();
    formData.append("title", title);
    formData.append("subject", subject);
    formData.append("academicYear", academicYear);
    formData.append("semester", semester);
    formData.append("file", file);

    try {
        const response = await fetch(`${API_BASE}/upload`, {
            method: "POST",
            credentials: "include",
            body: formData
        });

        if (response.status === 401) {
            window.location.href = LOGIN_PAGE;
            return;
        }

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Upload failed (${response.status}): ${errorText}`);
        }

        document.getElementById("pdfTitle").value = "";
        document.getElementById("uploadSubject").value = "";
        fileInput.value = "";

        setGlobalStatus("PDF uploaded successfully.", "success");
        await loadNotes();
    } catch (error) {
        console.error(error);
        setGlobalStatus(`Unable to upload PDF. ${error.message}`, "error");
    }
}

async function loadNotes() {
    const connected = await ensureApiBase();
    if (!connected) {
        renderLists([], []);
        document.getElementById("activeFilterText").textContent = "Backend not reachable. Start the Spring Boot app and refresh.";
        setGlobalStatus("Backend not reachable. Start the Spring Boot app and refresh.", "error");
        return;
    }

    try {
        const response = await fetch(`${API_BASE}/all`, {
            method: "GET",
            credentials: "include"
        });
        if (response.status === 401) {
            window.location.href = LOGIN_PAGE;
            return;
        }
        if (!response.ok) {
            throw new Error(`Load failed with status ${response.status}`);
        }

        allNotes = await response.json();
        applyFiltersAndRender();
    } catch (error) {
        console.error(error);
        renderLists([], []);
        document.getElementById("activeFilterText").textContent = `Could not load notes from server. ${error.message || ""}`;
        setGlobalStatus(`Could not load notes from server. ${error.message || ""}`, "error");
    }
}

function applyFiltersAndRender() {
    const year = document.getElementById("filterYear").value;
    const semester = document.getElementById("filterSemester").value;
    const subject = document.getElementById("filterSubject").value;

    if (!year || !semester || !subject) {
        document.getElementById("activeFilterText").textContent =
            "Please select Academic Year, Semester, and Subject to view notes.";
        renderLists([], []);
        return;
    }

    let filtered = allNotes.filter((note) => !!note.fileName);

    if (year) {
        filtered = filtered.filter((note) => note.academicYear === year);
    }
    if (semester) {
        filtered = filtered.filter((note) => note.semester === semester);
    }
    if (subject) {
        filtered = filtered.filter((note) => note.subject === subject);
    }

    const questionPapers = filtered.filter((note) => isQuestionPaper(note));
    const normalPdfs = filtered.filter((note) => !isQuestionPaper(note));

    const summary = [year || "All years", semester || "All semesters", subject || "All subjects"]
        .filter(Boolean)
        .join(" | ");

    document.getElementById("activeFilterText").textContent = `${summary} | Total PDFs: ${filtered.length}`;

    renderLists(normalPdfs, questionPapers);
}

function isQuestionPaper(note) {
    const text = `${note.title || ""} ${note.subject || ""} ${note.fileName || ""}`.toLowerCase();
    return /\bquestion[\s_-]*paper\b/.test(text);
}

function renderLists(notesPdfs, questionPapers) {
    const notesPdfList = document.getElementById("notesPdfList");
    const questionPdfList = document.getElementById("questionPdfList");

    notesPdfList.innerHTML = renderItems(notesPdfs, "No PDF notes found.");
    questionPdfList.innerHTML = renderItems(questionPapers, "No question papers found.");
}

function renderItems(items, emptyText) {
    if (items.length === 0) {
        return `<li class="empty-line">${escapeHtml(emptyText)}</li>`;
    }

    return items
        .map((note) => `
            <li class="item-row">
                <div class="item-main">
                    <a href="${API_BASE}/file/${note.id}" target="_blank" rel="noopener">${escapeHtml(note.fileName || note.title || "PDF")}</a>
                    <span> - ${escapeHtml(note.title || "Untitled")}</span>
                </div>
                ${currentUserIsAdmin
                    ? `<button type="button" class="btn danger mini" onclick="deleteNote(${Number(note.id)})">Delete</button>`
                    : ""}
            </li>
        `)
        .join("");
}

async function deleteNote(id) {
    if (!currentUserIsAdmin) {
        setGlobalStatus("Only admin can delete notes.", "error");
        return;
    }

    const noteId = Number(id);
    if (!Number.isFinite(noteId)) {
        setGlobalStatus("Invalid note id.", "error");
        return;
    }

    const shouldDelete = window.confirm("Delete this note? This cannot be undone.");
    if (!shouldDelete) {
        return;
    }

    const connected = await ensureApiBase();
    if (!connected) {
        setGlobalStatus("Backend not reachable. Start the Spring Boot app and refresh.", "error");
        return;
    }

    try {
        const response = await fetch(`${API_BASE}/${noteId}`, {
            method: "DELETE",
            credentials: "include"
        });
        if (response.status === 401) {
            window.location.href = LOGIN_PAGE;
            return;
        }
        if (response.status === 403) {
            const denyMessage = await response.text();
            throw new Error(denyMessage || "Only admin can delete notes.");
        }

        const message = await response.text();
        if (!response.ok) {
            throw new Error(message || `Delete failed (${response.status})`);
        }

        allNotes = allNotes.filter((note) => Number(note.id) !== noteId);
        applyFiltersAndRender();
        setGlobalStatus(message || "Note deleted successfully.", "success");
    } catch (error) {
        console.error(error);
        setGlobalStatus(`Unable to delete note. ${error.message || ""}`, "error");
    }
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

window.addEventListener("DOMContentLoaded", async () => {
    const authenticated = await checkSessionAndBindUser();
    if (!authenticated) {
        return;
    }

    setGlobalStatus("Ready. Select year, semester, and subject to browse notes.", "info");
    setupSelects();
    updateUploadSessionSelection();
    updateFilterSessionSelection();
    loadNotes();
});
