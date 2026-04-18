const DASHBOARD_PAGE = "dashboard.html";
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PASSWORD_LENGTH = 8;
let AUTH_BASE = "";
let authReady = false;

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

function setLoginStatus(message, type = "info") {
    const statusElement = document.getElementById("loginStatus");
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

function setAuthButtonsBusy(isBusy, activeButton) {
    const loginBtn = document.getElementById("loginBtn");
    const registerBtn = document.getElementById("registerBtn");
    if (loginBtn) {
        loginBtn.disabled = isBusy;
        loginBtn.textContent = isBusy && activeButton === "login" ? "Logging in..." : "Login";
    }
    if (registerBtn) {
        registerBtn.disabled = isBusy;
        registerBtn.textContent = isBusy && activeButton === "register" ? "Registering..." : "Register";
    }
}

async function readResponseBody(response) {
    const text = await response.text();
    if (!text) {
        return {};
    }

    try {
        return JSON.parse(text);
    } catch (error) {
        return { message: text };
    }
}

function getAuthPayload() {
    const nameInput = document.getElementById("loginName");
    const emailInput = document.getElementById("loginEmail");
    const passwordInput = document.getElementById("loginPassword");
    const name = (nameInput && nameInput.value ? nameInput.value : "").trim().replace(/\s+/g, " ");
    const email = (emailInput && emailInput.value ? emailInput.value : "").trim().toLowerCase();
    const password = passwordInput && passwordInput.value ? passwordInput.value : "";
    return { name, email, password };
}

function validateEmailPassword(email, password) {
    if (!EMAIL_REGEX.test(email)) {
        setLoginStatus("Please enter a valid email.", "error");
        return false;
    }
    if ((password || "").trim().length < MIN_PASSWORD_LENGTH) {
        setLoginStatus("Password must be at least 8 characters.", "error");
        return false;
    }
    return true;
}

function validateRegisterPayload(name, email, password) {
    if (!name) {
        setLoginStatus("Please enter your name for registration.", "error");
        return false;
    }
    return validateEmailPassword(email, password);
}

async function registerUser() {
    const authConnected = await ensureAuthBase();
    if (!authConnected) {
        setLoginStatus("Backend not reachable. Start the Spring Boot app and refresh.", "error");
        return;
    }

    const { name, email, password } = getAuthPayload();
    if (!validateRegisterPayload(name, email, password)) {
        return;
    }

    setAuthButtonsBusy(true, "register");
    setLoginStatus("Creating account...", "info");

    try {
        const response = await fetch(authUrl("/register"), {
            method: "POST",
            credentials: "include",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ name, email, password })
        });
        const data = await readResponseBody(response);
        if (!response.ok) {
            throw new Error(data.message || "Registration failed.");
        }

        setLoginStatus("Registration successful. Now click Login.", "success");
    } catch (error) {
        setLoginStatus(error.message || "Unable to register.", "error");
    } finally {
        setAuthButtonsBusy(false);
    }
}

async function login() {
    const authConnected = await ensureAuthBase();
    if (!authConnected) {
        setLoginStatus("Backend not reachable. Start the Spring Boot app and refresh.", "error");
        return;
    }

    const { name, email, password } = getAuthPayload();
    if (!validateEmailPassword(email, password)) {
        return;
    }

    setAuthButtonsBusy(true, "login");
    setLoginStatus("Signing in...", "info");

    try {
        const response = await fetch(authUrl("/login"), {
            method: "POST",
            credentials: "include",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ name, email, password })
        });
        const data = await readResponseBody(response);
        if (!response.ok) {
            throw new Error(data.message || "Login failed.");
        }

        setLoginStatus("Login successful. Redirecting...", "success");
        window.location.href = DASHBOARD_PAGE;
    } catch (error) {
        setLoginStatus(error.message || "Unable to login.", "error");
    } finally {
        setAuthButtonsBusy(false);
    }
}

async function redirectIfAlreadyLoggedIn() {
    try {
        const authConnected = await ensureAuthBase();
        if (!authConnected) {
            return;
        }

        const meResponse = await fetch(authUrl("/me"), {
            method: "GET",
            credentials: "include"
        });
        if (meResponse.ok) {
            window.location.href = DASHBOARD_PAGE;
        }
    } catch (error) {
        // Stay on login page when backend is down.
    }
}

window.addEventListener("DOMContentLoaded", () => {
    const loginBtn = document.getElementById("loginBtn");
    const registerBtn = document.getElementById("registerBtn");
    redirectIfAlreadyLoggedIn();

    const nameInput = document.getElementById("loginName");
    const emailInput = document.getElementById("loginEmail");
    const passwordInput = document.getElementById("loginPassword");

    if (loginBtn) {
        loginBtn.addEventListener("click", login);
    }
    if (registerBtn) {
        registerBtn.addEventListener("click", registerUser);
    }

    if (nameInput) {
        nameInput.addEventListener("keydown", (event) => {
            if (event.key === "Enter") {
                event.preventDefault();
                login();
            }
        });
    }

    if (emailInput) {
        emailInput.addEventListener("keydown", (event) => {
            if (event.key === "Enter") {
                event.preventDefault();
                login();
            }
        });
    }

    if (passwordInput) {
        passwordInput.addEventListener("keydown", (event) => {
            if (event.key === "Enter") {
                event.preventDefault();
                login();
            }
        });
    }
});
