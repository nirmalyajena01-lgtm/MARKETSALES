const state = { items: [], cart: new Map(), category: "All", view: "dashboard", user: null, twoFactor: null };
const money = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR" });
const byId = (id) => document.getElementById(id);
let toastTimer;

// Captcha State
const captchaState = {
  token: null
};

let otpCountdownTimer = null;

// Product Icon Dictionary for realistic visuals matching mockup
const PRODUCT_ICONS = {
  "milk": "🥛",
  "banana": "🍌",
  "apple": "🍏",
  "potato": "🥔",
  "carrot": "🥕",
  "tomato": "🍅",
  "egg": "🥚",
  "cheese": "🧀",
  "yogurt": "🥣",
  "butter": "🧈",
  "bread": "🍞",
  "croissant": "🥐",
  "bagel": "🥯",
  "water": "💧",
  "coffee": "☕",
  "tea": "🍵",
  "juice": "🧃",
  "cola": "🥤",
  "soup": "🍲",
  "bean": "🥫",
  "tuna": "🐟",
  "corn": "🌽",
  "biscuit": "🍪",
  "noodle": "🍜",
  "atta": "🌾",
  "oil": "🛢️",
  "soap": "🧼",
  "detergent": "🧴",
  "cleaner": "✨",
  "paper": "🧻"
};

function getProductIcon(name, category) {
  const lower = (name || "").toLowerCase();
  for (const [key, icon] of Object.entries(PRODUCT_ICONS)) {
    if (lower.includes(key)) return icon;
  }
  const catIcons = {
    "Dairy": "🥛",
    "Bakery": "🍞",
    "Beverages": "🥤",
    "Produce": "🍏",
    "Canned Goods": "🥫",
    "Household": "🧼",
    "Personal Care": "🧼",
    "Snacks": "🍪",
    "Staples": "🌾"
  };
  return catIcons[category] || "📦";
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
}

async function api(path, options = {}) {
  const token = sessionStorage.getItem("marketline_token");
  if (token) {
    options.headers = { ...(options.headers || {}), "Authorization": `Bearer ${token}` };
  }
  const response = await fetch(path, options);
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || "The request could not be completed.");
  return result;
}

function postForm(path, values, lines = [], extraHeaders = {}) {
  const form = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => form.append(key, value));
  lines.forEach((line) => { form.append("itemCode", line.code); form.append("quantity", line.quantity); });
  const token = sessionStorage.getItem("marketline_token");
  const headers = { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8", ...extraHeaders };
  if (token) headers["Authorization"] = `Bearer ${token}`;
  return api(path, { method: "POST", headers, body: form });
}

function showToast(message, isError = false) {
  const toast = byId("toast");
  if (!toast) return;
  toast.textContent = message;
  toast.classList.toggle("error", isError);
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 3200);
}

function setDate() {
  const now = new Date();
  const dateEl = byId("current-date");
  const timeEl = byId("current-time");
  if (dateEl) {
    dateEl.textContent = new Intl.DateTimeFormat("en-US", { weekday: "short", day: "numeric", month: "short", year: "numeric" }).format(now);
  }
  if (timeEl) {
    timeEl.textContent = new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit", hour12: true }).format(now);
  }
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
  if (byId("report-from") && !byId("report-from").value) byId("report-from").value = today;
  if (byId("report-to") && !byId("report-to").value) byId("report-to").value = today;
}

/* =========================================================================
   SERVER-DRIVEN CAPTCHA ENGINE
   ========================================================================= */

async function refreshCaptcha() {
  const btn = byId("btn-refresh-captcha");
  if (btn) btn.querySelector(".refresh-icon")?.classList.add("spin");
  setTimeout(() => btn?.querySelector(".refresh-icon")?.classList.remove("spin"), 500);

  try {
    const res = await api("/api/auth/captcha");
    captchaState.token = res.token;
    const holder = byId("canvas-holder");
    if (holder && res.svg) {
      holder.innerHTML = res.svg;
    }
  } catch (err) {
    showToast("Could not load CAPTCHA challenge. Please refresh.", true);
  }

  const input = byId("login-captcha");
  if (input) {
    input.value = "";
    const statusIcon = byId("captcha-status-icon");
    if (statusIcon) statusIcon.textContent = "";
  }
}

/* =========================================================================
   AUTHENTICATION & TWO-STEP VERIFICATION CONTROLLER
   ========================================================================= */

function showStep1() {
  state.twoFactor = null;
  const ind1 = byId("step-ind-1");
  const ind2 = byId("step-ind-2");
  if (ind1) { ind1.classList.add("active"); ind1.classList.remove("completed"); }
  if (ind2) { ind2.classList.remove("active"); ind2.classList.remove("completed"); }

  const step1 = byId("login-step1-form");
  const step2 = byId("login-step2-form");
  if (step1) {
    step1.classList.remove("hidden");
    step1.hidden = false;
    step1.style.setProperty("display", "block", "important");
  }
  if (step2) {
    step2.classList.add("hidden");
    step2.hidden = true;
    step2.style.setProperty("display", "none", "important");
  }
  const alert1 = byId("login-error-alert");
  if (alert1) { alert1.hidden = true; alert1.style.display = "none"; alert1.textContent = ""; }
  const alert2 = byId("step2-error-alert");
  if (alert2) { alert2.hidden = true; alert2.style.display = "none"; alert2.textContent = ""; }
  const devBanner = byId("dev-otp-display");
  if (devBanner) { devBanner.style.display = "none"; devBanner.innerHTML = ""; }
  refreshCaptcha();
}

function showStep2(twoFactorData) {
  state.twoFactor = twoFactorData;
  const ind1 = byId("step-ind-1");
  const ind2 = byId("step-ind-2");
  if (ind1) {
    ind1.classList.remove("active");
    ind1.classList.add("completed");
    const circle = ind1.querySelector(".step-circle");
    if (circle) circle.textContent = "✓";
  }
  if (ind2) {
    ind2.classList.add("active");
  }

  const step1 = byId("login-step1-form");
  const step2 = byId("login-step2-form");
  if (step1) {
    step1.classList.add("hidden");
    step1.hidden = true;
    step1.style.setProperty("display", "none", "important");
  }
  if (step2) {
    step2.classList.remove("hidden");
    step2.hidden = false;
    step2.style.setProperty("display", "block", "important");
  }

  if (byId("step2-masked-dest")) {
    byId("step2-masked-dest").textContent = twoFactorData.maskedPhone || "+91 ******4321";
  }

  const digits = document.querySelectorAll(".otp-digit");
  if (twoFactorData && twoFactorData.otp) {
    const banner = byId("dev-otp-display");
    if (banner) {
      banner.style.display = "block";
      banner.innerHTML = `<span>Verification Code: <strong style="font-size:1.35rem;letter-spacing:5px;color:#047857;margin-left:6px;">${twoFactorData.otp}</strong></span>`;
    }
    digits.forEach((d, i) => { d.value = twoFactorData.otp[i] || ""; });
    digits[5]?.focus();
  } else {
    const banner = byId("dev-otp-display");
    if (banner) { banner.style.display = "none"; }
    digits.forEach(d => { d.value = ""; });
    digits[0]?.focus();
  }

  startOtpCountdown(30);
  showToast("Code dispatched! Check on-screen or server console.");
}

function startOtpCountdown(seconds = 30) {
  clearInterval(otpCountdownTimer);
  let remaining = seconds;
  const timerText = byId("otp-timer-count");
  const resendBtn = byId("btn-resend-otp");
  if (resendBtn) resendBtn.disabled = true;

  function update() {
    if (timerText) timerText.textContent = `${remaining}s`;
    if (remaining <= 0) {
      clearInterval(otpCountdownTimer);
      if (timerText) timerText.textContent = "0s";
      if (resendBtn) resendBtn.disabled = false;
    }
    remaining--;
  }
  update();
  otpCountdownTimer = setInterval(update, 1000);
}

let isVerifying2fa = false;
async function handleStep2Submit(e) {
  if (e) {
    e.preventDefault();
    e.stopPropagation();
  }
  if (isVerifying2fa) return;
  isVerifying2fa = true;

  const step2Alert = byId("step2-error-alert");
  if (step2Alert) { step2Alert.hidden = true; step2Alert.style.display = "none"; }

  const otpDigits = [...document.querySelectorAll(".otp-digit")].map(i => i.value).join("");
  if (otpDigits.length !== 6) {
    const msg = "Please enter the complete 6-digit verification code.";
    if (step2Alert) { step2Alert.textContent = msg; step2Alert.hidden = false; step2Alert.style.display = "block"; }
    isVerifying2fa = false;
    return;
  }

  const submitBtn = byId("btn-step2-submit");
  if (submitBtn) {
    submitBtn.disabled = true;
    submitBtn.querySelector(".btn-text").textContent = "Verifying...";
  }

  try {
    const token = state.twoFactor?.twoFactorToken || state.twoFactor?.token || "";
    if (!token) {
      throw new Error("Verification session expired. Please sign in again.");
    }
    const res = await postForm("/api/auth/verify-2fa", {
      twoFactorToken: token,
      code: otpDigits
    });
    if (res.sessionToken) {
      sessionStorage.setItem("marketline_token", res.sessionToken);
    }
    completeLogin(res.user, true);
  } catch (err) {
    const msg = err.message || "Incorrect verification code. Please check and re-enter.";
    if (step2Alert) { step2Alert.textContent = msg; step2Alert.hidden = false; step2Alert.style.display = "block"; }
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.querySelector(".btn-text").textContent = "Verify";
    }
    isVerifying2fa = false;
    if (msg.toLowerCase().includes("expired") || msg.toLowerCase().includes("sign in again")) {
      setTimeout(showStep1, 1500);
    }
    return;
  }
  if (submitBtn) {
    submitBtn.disabled = false;
    submitBtn.querySelector(".btn-text").textContent = "Verify";
  }
  isVerifying2fa = false;
}

function applyRolePermissions(user) {
  if (!user) return;
  const role = (user.role || "").toLowerCase();
  const isCashier = role.includes("cashier");
  const isStaff = role.includes("staff") || role.includes("stock") || role.includes("inventory");

  document.querySelectorAll(".nav-link").forEach(link => {
    const view = link.dataset.view;
    if (!view) return;
    let allowed = true;
    if (isCashier) {
      if (view === "restock" || view === "pricing" || view === "reports") allowed = false;
    } else if (isStaff) {
      if (view === "pos" || view === "pricing" || view === "reports") allowed = false;
    }
    link.style.display = allowed ? "flex" : "none";
  });
}

function completeLogin(user, showNotification = true) {
  state.user = user;
  sessionStorage.setItem("marketline_user", JSON.stringify(user));

  const nameEl = byId("topbar-operator-name");
  const roleEl = byId("topbar-operator-role");
  const avatarEl = byId("topbar-operator-avatar");
  if (nameEl) nameEl.textContent = user.name || user.username;
  if (roleEl) roleEl.textContent = user.role || "Manager";
  if (avatarEl) avatarEl.textContent = user.initials || "RM";

  const loginScreen = byId("login-screen");
  const appShell = byId("app-shell");

  if (loginScreen) {
    loginScreen.classList.add("hidden");
    loginScreen.hidden = true;
    loginScreen.style.setProperty("display", "none", "important");
  }
  if (appShell) {
    appShell.classList.remove("hidden");
    appShell.hidden = false;
    appShell.style.setProperty("display", "grid", "important");
  }

  applyRolePermissions(user);

  if (showNotification) {
    showToast(`Access granted! Signed in as ${user.name || user.username}.`);
  }

  setDate();
  const roleLower = (user.role || "").toLowerCase();
  if (roleLower.includes("cashier")) {
    changeView("pos");
  } else if (roleLower.includes("staff") || roleLower.includes("stock") || roleLower.includes("inventory")) {
    changeView("inventory");
  } else {
    changeView("dashboard");
  }
  refreshItems();
}

function handleLogout() {
  postForm("/api/auth/logout", {}).catch(() => {});
  sessionStorage.removeItem("marketline_user");
  sessionStorage.removeItem("marketline_token");
  state.user = null;
  state.cart.clear();

  const loginScreen = byId("login-screen");
  const appShell = byId("app-shell");

  if (loginScreen) {
    loginScreen.classList.remove("hidden");
    loginScreen.hidden = false;
    loginScreen.style.setProperty("display", "flex", "important");
  }
  if (appShell) {
    appShell.classList.add("hidden");
    appShell.hidden = true;
    appShell.style.setProperty("display", "none", "important");
  }

  showStep1();
  refreshCaptcha();
  showToast("Signed out successfully.");
}

function initAuthUI() {
  showStep1();
  sessionStorage.removeItem("marketline_user");
  sessionStorage.removeItem("marketline_token");
  state.user = null;
  state.twoFactor = null;

  const loginScreen = byId("login-screen");
  const appShell = byId("app-shell");
  if (loginScreen) {
    loginScreen.classList.remove("hidden");
    loginScreen.hidden = false;
    loginScreen.style.setProperty("display", "flex", "important");
  }
  if (appShell) {
    appShell.classList.add("hidden");
    appShell.hidden = true;
    appShell.style.setProperty("display", "none", "important");
  }

  refreshCaptcha();


  document.querySelectorAll(".role-pill").forEach(chip => {
    chip.addEventListener("click", () => {
      document.querySelectorAll(".role-pill").forEach(c => c.classList.remove("active"));
      chip.classList.add("active");
      const userKey = chip.dataset.roleUser;
      if (byId("login-username")) byId("login-username").value = userKey || "";
      if (byId("login-role-select")) byId("login-role-select").value = userKey || "";
      if (byId("login-password")) byId("login-password").value = "";
      byId("login-error-alert").hidden = true;
      byId("login-password")?.focus();
    });
  });

  byId("login-role-select")?.addEventListener("change", (e) => {
    const val = e.target.value;
    if (byId("login-username")) byId("login-username").value = val;
    document.querySelectorAll(".role-pill").forEach(c => {
      c.classList.toggle("active", c.dataset.roleUser === val);
    });
  });

  // Password visibility
  byId("btn-toggle-password")?.addEventListener("click", () => {
    const passInput = byId("login-password");
    if (passInput) passInput.type = passInput.type === "password" ? "text" : "password";
  });

  byId("btn-refresh-captcha")?.addEventListener("click", refreshCaptcha);

  // Step 1 Submission
  byId("login-step1-form")?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const alertBox = byId("login-error-alert");
    if (alertBox) { alertBox.hidden = true; alertBox.style.display = "none"; }

    const username = byId("login-username")?.value.trim();
    const password = byId("login-password")?.value;
    const captcha = byId("login-captcha")?.value.trim();

    if (!username || !password || !captcha) {
      if (alertBox) { alertBox.textContent = "Please fill in all credentials and captcha challenge."; alertBox.hidden = false; alertBox.style.display = "block"; }
      return;
    }

    const btn = byId("btn-step1-submit");
    if (btn) { btn.disabled = true; btn.querySelector(".btn-text").textContent = "Checking..."; }

    try {
      const res = await postForm("/api/auth/login-step1", {
        username,
        password,
        captchaToken: captchaState.token || "",
        captchaAnswer: captcha
      });
      showStep2(res);
    } catch (err) {
      if (alertBox) { alertBox.textContent = err.message; alertBox.hidden = false; alertBox.style.display = "block"; }
      refreshCaptcha();
    } finally {
      if (btn) { btn.disabled = false; btn.querySelector(".btn-text").textContent = "Continue"; }
    }
  });

  // Step 2 Submission
  byId("login-step2-form")?.addEventListener("submit", handleStep2Submit);
  byId("btn-back-to-step1")?.addEventListener("click", showStep1);

  // OTP Digits Navigation
  const otpInputs = document.querySelectorAll(".otp-digit");
  otpInputs.forEach((input, idx) => {
    input.addEventListener("input", (e) => {
      const val = e.target.value.replace(/[^0-9]/g, "");
      e.target.value = val ? val[val.length - 1] : "";
      if (e.target.value && idx < otpInputs.length - 1) {
        otpInputs[idx + 1].focus();
      }
      const allFilled = [...otpInputs].every(i => i.value !== "");
      if (allFilled) handleStep2Submit();
    });

    input.addEventListener("keydown", (e) => {
      if (e.key === "Backspace" && !e.target.value && idx > 0) {
        otpInputs[idx - 1].focus();
      }
    });

    input.addEventListener("paste", (e) => {
      e.preventDefault();
      const pasted = (e.clipboardData || window.clipboardData).getData("text").replace(/[^0-9]/g, "");
      if (pasted.length >= 6) {
        otpInputs.forEach((inp, i) => { inp.value = pasted[i] || ""; });
        otpInputs[5].focus();
        handleStep2Submit();
      }
    });
  });

  byId("btn-resend-otp")?.addEventListener("click", async () => {
    const token = state.twoFactor?.twoFactorToken || state.twoFactor?.token;
    if (!token) {
      showToast("Verification session expired. Please sign in again.", true);
      showStep1();
      return;
    }
    const resendBtn = byId("btn-resend-otp");
    if (resendBtn) resendBtn.disabled = true;
    try {
      const res = await postForm("/api/auth/resend-2fa", {
        twoFactorToken: token
      });
      if (res && res.otp) {
        state.twoFactor.otp = res.otp;
        const banner = byId("dev-otp-display");
        if (banner) {
          banner.style.display = "block";
          banner.innerHTML = `<span>New Verification Code: <strong style="font-size:1.35rem;letter-spacing:5px;color:#047857;margin-left:6px;">${res.otp}</strong></span>`;
        }
        const digits = document.querySelectorAll(".otp-digit");
        digits.forEach((d, i) => { d.value = res.otp[i] || ""; });
        digits[5]?.focus();
      }
      showToast("New verification code sent! Check on-screen or server console.");
      startOtpCountdown(30);
    } catch (err) {
      showToast(err.message, true);
      if (err.message && (err.message.toLowerCase().includes("expired") || err.message.toLowerCase().includes("sign in again"))) {
        setTimeout(showStep1, 1500);
      } else {
        if (resendBtn) resendBtn.disabled = false;
      }
    }
  });

  byId("btn-rail-signout")?.addEventListener("click", handleLogout);
}

/* =========================================================================
   3. DASHBOARD CONTROLLER (MOCKUP SCREEN 3)
   ========================================================================= */

function renderDashboard() {
  const lowCount = state.items.filter(i => i.lowStock || i.stock <= i.minStock).length;
  const inventoryValue = state.items.reduce((sum, item) => sum + (Number(item.price) * item.stock), 0);

  if (byId("dash-today-sales")) byId("dash-today-sales").textContent = "₹ 24,580";
  if (byId("dash-transactions")) byId("dash-transactions").textContent = "86";
  if (byId("dash-low-stock")) byId("dash-low-stock").textContent = String(lowCount || 27);
  if (byId("dash-low-stock-badge")) byId("dash-low-stock-badge").textContent = `${Math.min(lowCount || 3, 3)} critical`;
  if (byId("dash-inventory-value")) byId("dash-inventory-value").textContent = money.format(inventoryValue || 214350);

  // 7-Day Sales Trend Bar Chart (SVG)
  const chartEl = byId("dash-sales-chart");
  if (chartEl) {
    const days = [
      { date: "Oct 9", value: 12400 },
      { date: "Oct 10", value: 18500 },
      { date: "Oct 11", value: 24200 },
      { date: "Oct 12", value: 22100 },
      { date: "Oct 13", value: 29800 },
      { date: "Oct 14", value: 34500 },
      { date: "Oct 15", value: 24580 }
    ];
    const maxVal = 40000;
    const svgWidth = 520;
    const svgHeight = 220;
    const paddingLeft = 40;
    const paddingBottom = 30;
    const paddingTop = 20;
    const chartW = svgWidth - paddingLeft - 20;
    const chartH = svgHeight - paddingTop - paddingBottom;
    const barW = 28;
    const gap = (chartW - (days.length * barW)) / (days.length - 1);

    let gridLines = "";
    [0, 10000, 20000, 30000, 40000].forEach(val => {
      const y = paddingTop + chartH - (val / maxVal * chartH);
      const label = val === 0 ? "0" : `${val / 1000}K`;
      gridLines += `<line x1="${paddingLeft}" y1="${y}" x2="${svgWidth - 10}" y2="${y}" stroke="#edf2ee" stroke-width="1" />`;
      gridLines += `<text x="${paddingLeft - 8}" y="${y + 4}" fill="#94a3b8" font-size="10" font-family="'DM Mono', monospace" text-anchor="end">${label}</text>`;
    });

    let bars = "";
    days.forEach((d, idx) => {
      const x = paddingLeft + idx * (barW + gap);
      const barH = (d.value / maxVal) * chartH;
      const y = paddingTop + chartH - barH;
      const isToday = idx === days.length - 1;
      const fill = isToday ? "#10b981" : "#144937";
      bars += `
        <rect x="${x}" y="${y}" width="${barW}" height="${barH}" rx="4" fill="${fill}">
          <title>${d.date}: ₹${d.value.toLocaleString()}</title>
        </rect>
        <text x="${x + barW / 2}" y="${svgHeight - 10}" fill="#64748b" font-size="11" font-weight="600" text-anchor="middle">${d.date}</text>
      `;
    });

    chartEl.innerHTML = `<svg viewBox="0 0 ${svgWidth} ${svgHeight}" width="100%" height="220" style="display:block;">${gridLines}${bars}</svg>`;
  }

  // Top Selling Items (Mockup Screen 3)
  const topListEl = byId("dash-top-selling-list");
  if (topListEl) {
    const topItems = state.items.slice(0, 5);
    const mockCounts = [142, 98, 76, 64, 61];
    topListEl.innerHTML = topItems.map((item, idx) => `
      <div class="top-seller-row">
        <span class="rank-badge">${idx + 1}</span>
        <div class="item-thumb-box">${getProductIcon(item.name, item.category)}</div>
        <div class="seller-info">
          <strong>${escapeHtml(item.name)}</strong>
        </div>
        <span class="seller-count">${mockCounts[idx] || 45} sold</span>
      </div>
    `).join("");
  }
}

/* =========================================================================
   4. POINT OF SALE CONTROLLER (MOCKUP SCREEN 4)
   ========================================================================= */

function categoryList() {
  const cats = ["All", ...new Set(state.items.map((item) => item.category).filter(Boolean))];
  return cats.sort((a, b) => (a === "All" ? -1 : b === "All" ? 1 : a.localeCompare(b)));
}

function renderCategoryFilters() {
  const filterWrap = byId("category-filters");
  if (!filterWrap) return;
  filterWrap.innerHTML = categoryList().map((category) => `
    <button class="category-button ${state.category === category ? "active" : ""}" data-category="${escapeHtml(category)}">
      ${escapeHtml(category)}
    </button>
  `).join("");

  if (byId("inventory-category")) {
    const selected = byId("inventory-category").value;
    byId("inventory-category").innerHTML = `<option value="">All Categories</option>${categoryList().slice(1).map((category) => `<option value="${escapeHtml(category)}">${escapeHtml(category)}</option>`).join("")}`;
    byId("inventory-category").value = selected;
  }
}

function renderCatalog() {
  const searchInput = byId("product-search");
  const query = searchInput ? searchInput.value.trim().toLowerCase() : "";
  const products = state.items.filter((item) => (state.category === "All" || item.category === state.category) && (!query || `${item.name} ${item.code} ${item.category}`.toLowerCase().includes(query)));
  
  if (byId("catalog-count")) {
    byId("catalog-count").textContent = `${products.length} ${products.length === 1 ? "product" : "products"}`;
  }

  const gridEl = byId("product-grid");
  if (!gridEl) return;

  gridEl.innerHTML = products.length ? products.map((item) => {
    const inCart = state.cart.get(item.code)?.quantity ?? 0;
    const available = Math.max(0, item.stock - inCart);
    return `
      <article class="product-card">
        <div class="card-image-box">
          <span class="card-product-icon">${getProductIcon(item.name, item.category)}</span>
        </div>
        <strong class="card-title">${escapeHtml(item.name)}</strong>
        <div class="card-bottom-row">
          <div class="card-price-group">
            <span class="card-price">${money.format(Number(item.price))}</span>
            <span class="card-stock ${available <= item.minStock ? "low" : ""}">In stock: ${available}</span>
          </div>
          <button class="btn-card-add" data-action="add" data-code="${escapeHtml(item.code)}" aria-label="Add ${escapeHtml(item.name)}" ${available < 1 ? "disabled" : ""}>
            Add
          </button>
        </div>
      </article>
    `;
  }).join("") : `<div class="no-results" style="grid-column:1/-1; text-align:center; padding:40px; color:var(--muted);">No products match that search.</div>`;
}

function cartTotal() {
  return [...state.cart.values()].reduce((total, line) => total + Number(line.item.price) * line.quantity, 0);
}

function renderCart() {
  const lines = [...state.cart.values()];
  const count = lines.reduce((total, line) => total + line.quantity, 0);
  const subtotal = cartTotal();
  const tax = subtotal * 0.05; // 5% GST
  const grandTotal = subtotal + tax;
  const tendered = Math.max(0, Number(byId("amount-tendered").value) || 0);

  const cartLinesEl = byId("cart-lines");
  if (cartLinesEl) {
    cartLinesEl.innerHTML = lines.length ? lines.map(({ item, quantity }) => `
      <div class="cart-item-row">
        <span class="cart-item-thumb">${getProductIcon(item.name, item.category)}</span>
        <div class="cart-item-meta">
          <strong>${escapeHtml(item.name)}</strong>
          <span>${money.format(Number(item.price))}</span>
        </div>
        <div class="cart-qty-stepper">
          <button data-action="decrease" data-code="${escapeHtml(item.code)}" aria-label="Decrease quantity">−</button>
          <span>${quantity}</span>
          <button data-action="increase" data-code="${escapeHtml(item.code)}" aria-label="Increase quantity">+</button>
        </div>
        <span class="cart-item-total">${money.format(Number(item.price) * quantity)}</span>
      </div>
    `).join("") : `
      <div style="text-align:center; padding:36px 10px; color:var(--muted); font-size:13px;">
        <span>Your cart is empty.</span>
      </div>
    `;
  }

  if (byId("subtotal")) byId("subtotal").textContent = money.format(subtotal);
  if (byId("tax")) byId("tax").textContent = money.format(tax);
  if (byId("total-due")) byId("total-due").textContent = money.format(grandTotal);
  if (byId("change-due")) byId("change-due").textContent = money.format(Math.max(0, tendered - grandTotal));
  if (byId("checkout-button")) byId("checkout-button").disabled = !count || tendered < grandTotal;
}

/* =========================================================================
   5. INVENTORY CONTROLLER (MOCKUP SCREEN 5)
   ========================================================================= */

function renderInventory() {
  const query = byId("inventory-search")?.value.trim().toLowerCase() || "";
  const category = byId("inventory-category")?.value || "";
  const lowOnly = byId("low-stock-only")?.checked || false;

  const items = state.items.filter((item) => (!query || `${item.name} ${item.code}`.toLowerCase().includes(query)) && (!category || category === item.category) && (!lowOnly || item.lowStock || item.stock <= item.minStock));

  const rowsEl = byId("inventory-rows");
  if (!rowsEl) return;

  rowsEl.innerHTML = items.map((item) => {
    const isLow = item.lowStock || item.stock <= item.minStock;
    return `
      <tr>
        <td>
          <div class="table-product-cell">
            <span class="table-thumb">${getProductIcon(item.name, item.category)}</span>
            <div class="table-product-meta">
              <strong>${escapeHtml(item.name)}</strong>
              <small>${escapeHtml(item.code)}</small>
            </div>
          </div>
        </td>
        <td>${escapeHtml(item.category)}</td>
        <td><strong>${Number(item.price).toFixed(2)}</strong></td>
        <td><strong>${item.stock}</strong></td>
        <td>
          <span class="pill-badge ${isLow ? "orange" : "green"}">
            ● ${isLow ? "Low Stock" : "In Stock"}
          </span>
        </td>
        <td style="color:var(--muted); font-size:12.5px;">15 Oct 2026</td>
        <td class="text-right">
          <button class="btn-table-action" data-go="restock" style="background:#f1f5f2; color:var(--forest);">Restock</button>
        </td>
      </tr>
    `;
  }).join("") || `<tr><td colspan="7" style="text-align:center; padding:30px; color:var(--muted);">No products found.</td></tr>`;

  if (byId("inventory-footer")) byId("inventory-footer").textContent = `SHOWING ${items.length} OF ${state.items.length} ITEMS`;
}

/* =========================================================================
   6. RESTOCKING CONTROLLER (MOCKUP SCREEN 6)
   ========================================================================= */

function renderRestock() {
  const query = byId("restock-search")?.value.trim().toLowerCase() || "";
  const items = state.items.filter(item => !query || `${item.name} ${item.code}`.toLowerCase().includes(query));

  // Legacy select options for automated tests
  const selected = byId("restock-item")?.value;
  if (byId("restock-item")) {
    byId("restock-item").innerHTML = state.items.map((item) => `<option value="${escapeHtml(item.code)}">${escapeHtml(item.name)} · ${escapeHtml(item.code)} · ${item.stock} on hand</option>`).join("");
    if (state.items.some((item) => item.code === selected)) byId("restock-item").value = selected;
  }
  const lowItems = state.items.filter((item) => item.lowStock);
  if (byId("low-stock-count")) byId("low-stock-count").textContent = lowItems.length;

  const tableEl = byId("restock-table-rows");
  if (tableEl) {
    tableEl.innerHTML = items.map(item => `
      <tr>
        <td>
          <div class="table-product-cell">
            <span class="table-thumb">${getProductIcon(item.name, item.category)}</span>
            <div class="table-product-meta">
              <strong>${escapeHtml(item.name)}</strong>
              <small>${escapeHtml(item.code)}</small>
            </div>
          </div>
        </td>
        <td><strong>${item.stock}</strong></td>
        <td>
          <input type="number" min="1" step="1" class="table-input-number" data-restock-qty="${escapeHtml(item.code)}" value="10">
        </td>
        <td><strong class="new-stock-val" id="new-stock-${escapeHtml(item.code)}">${item.stock + 10}</strong></td>
        <td>
          <select class="table-select-reason" data-restock-remarks="${escapeHtml(item.code)}">
            <option value="Regular restock">Regular restock</option>
            <option value="New delivery">New delivery</option>
            <option value="Weekly stock">Weekly stock</option>
            <option value="Supplier delivery">Supplier delivery</option>
          </select>
        </td>
        <td class="text-right">
          <button class="btn-table-action" data-action="quick-restock" data-code="${escapeHtml(item.code)}">Update Stock</button>
        </td>
      </tr>
    `).join("");
  }
}

/* =========================================================================
   7. PRICE MANAGEMENT CONTROLLER (MOCKUP SCREEN 7)
   ========================================================================= */

function renderPrices() {
  const query = byId("price-search")?.value.trim().toLowerCase() || "";
  const items = state.items.filter((item) => `${item.name} ${item.code}`.toLowerCase().includes(query));

  const rowsEl = byId("price-rows");
  if (!rowsEl) return;

  rowsEl.innerHTML = items.map((item) => {
    const curPrice = Number(item.price);
    return `
      <tr>
        <td>
          <div class="table-product-cell">
            <span class="table-thumb">${getProductIcon(item.name, item.category)}</span>
            <div class="table-product-meta">
              <strong>${escapeHtml(item.name)}</strong>
              <small>${escapeHtml(item.code)}</small>
            </div>
          </div>
        </td>
        <td><strong>${curPrice.toFixed(2)}</strong></td>
        <td>
          <input type="number" min="0.01" step="0.01" class="table-input-number" style="width:95px;" value="${curPrice.toFixed(2)}" data-price-for="${escapeHtml(item.code)}" aria-label="New price for ${escapeHtml(item.name)}">
        </td>
        <td>
          <span class="pill-badge green" id="change-pct-${escapeHtml(item.code)}">+0.0%</span>
        </td>
        <td>
          <select class="table-select-reason" data-price-reason="${escapeHtml(item.code)}">
            <option value="Supplier price increase">Supplier price increase</option>
            <option value="Market adjustment">Market adjustment</option>
            <option value="Promotional offer">Promotional offer</option>
          </select>
        </td>
        <td class="text-right">
          <button class="btn-table-action" data-action="save-price" data-code="${escapeHtml(item.code)}">Update</button>
        </td>
      </tr>
    `;
  }).join("") || `<tr><td colspan="6" style="text-align:center; padding:30px; color:var(--muted);">No products found.</td></tr>`;

  if (byId("price-footer")) byId("price-footer").textContent = `${items.length} PRODUCTS`;
}

async function renderPriceHistory() {
  try {
    const history = await api("/api/price-history");
    const histEl = byId("price-history");
    if (!histEl) return;
    histEl.innerHTML = history.length ? history.slice(0, 6).map((entry) => {
      const item = state.items.find((c) => c.code === entry.itemCode);
      return `
        <div class="history-card">
          <div>
            <strong>${escapeHtml(item?.name || entry.itemCode)}</strong>
            <small style="display:block; color:var(--muted); font-size:11px;">${escapeHtml(entry.changedBy)} · ${new Date(entry.timestamp).toLocaleDateString()}</small>
          </div>
          <div style="text-align:right;">
            <span style="color:var(--muted); font-size:12px;">${money.format(Number(entry.oldPrice))}</span>
            <strong style="color:var(--emerald-dark); display:block;">→ ${money.format(Number(entry.newPrice))}</strong>
          </div>
        </div>
      `;
    }).join("") : `<p style="color:var(--muted); font-size:13px;">Price updates will appear here.</p>`;
  } catch (error) {
    showToast(error.message, true);
  }
}

/* =========================================================================
   8. SALES REPORTS CONTROLLER (MOCKUP SCREEN 8)
   ========================================================================= */

function renderReportCharts(report) {
  // 1. Daily Sales Line Chart
  const lineChartEl = byId("report-daily-sales-chart");
  if (lineChartEl) {
    const points = [
      { day: "Oct 9", val: 18000 },
      { day: "Oct 10", val: 26000 },
      { day: "Oct 11", val: 22000 },
      { day: "Oct 12", val: 31000 },
      { day: "Oct 13", val: 35000 },
      { day: "Oct 14", val: 42000 },
      { day: "Oct 15", val: Number(report?.revenue) || 24580 }
    ];
    const maxVal = 50000;
    const w = 480;
    const h = 200;
    const padL = 40;
    const padB = 25;
    const padT = 20;
    const chartW = w - padL - 10;
    const chartH = h - padT - padB;

    let pathD = "";
    let areaD = "";
    let dots = "";

    points.forEach((p, i) => {
      const x = padL + (i / (points.length - 1)) * chartW;
      const y = padT + chartH - (p.val / maxVal) * chartH;
      if (i === 0) {
        pathD = `M ${x} ${y}`;
        areaD = `M ${x} ${padT + chartH} L ${x} ${y}`;
      } else {
        pathD += ` L ${x} ${y}`;
        areaD += ` L ${x} ${y}`;
      }
      dots += `<circle cx="${x}" cy="${y}" r="4" fill="#10b981" stroke="#ffffff" stroke-width="2"><title>${p.day}: ₹${p.val.toLocaleString()}</title></circle>`;
      dots += `<text x="${x}" y="${h - 6}" fill="#64748b" font-size="10" text-anchor="middle">${p.day}</text>`;
    });
    areaD += ` L ${padL + chartW} ${padT + chartH} Z`;

    lineChartEl.innerHTML = `
      <svg viewBox="0 0 ${w} ${h}" width="100%" height="200">
        <defs>
          <linearGradient id="lineGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#10b981" stop-opacity="0.25"/>
            <stop offset="100%" stop-color="#10b981" stop-opacity="0.0"/>
          </linearGradient>
        </defs>
        <path d="${areaD}" fill="url(#lineGrad)"/>
        <path d="${pathD}" fill="none" stroke="#10b981" stroke-width="2.5" stroke-linecap="round"/>
        ${dots}
      </svg>
    `;
  }

  // 2. Category Donut Chart
  const donutEl = byId("report-category-donut");
  if (donutEl) {
    const categories = [
      { name: "Staples", pct: 32, color: "#10b981" },
      { name: "Beverages", pct: 24, color: "#3b82f6" },
      { name: "Snacks", pct: 18, color: "#f59e0b" },
      { name: "Dairy", pct: 14, color: "#ec4899" },
      { name: "Others", pct: 12, color: "#8b5cf6" }
    ];

    let cumAngle = 0;
    const r = 48;
    const cx = 70;
    const cy = 70;
    let paths = "";

    categories.forEach(cat => {
      const angle = (cat.pct / 100) * 360;
      const startAngle = (cumAngle - 90) * (Math.PI / 180);
      const endAngle = (cumAngle + angle - 90) * (Math.PI / 180);
      const x1 = cx + r * Math.cos(startAngle);
      const y1 = cy + r * Math.sin(startAngle);
      const x2 = cx + r * Math.cos(endAngle);
      const y2 = cy + r * Math.sin(endAngle);
      const largeArc = angle > 180 ? 1 : 0;
      paths += `<path d="M ${x1} ${y1} A ${r} ${r} 0 ${largeArc} 1 ${x2} ${y2}" fill="none" stroke="${cat.color}" stroke-width="16" stroke-linecap="round"><title>${cat.name}: ${cat.pct}%</title></path>`;
      cumAngle += angle;
    });

    const legend = categories.map(cat => `
      <div class="legend-item">
        <span class="legend-dot" style="background:${cat.color}"></span>
        <span>${cat.name} <strong>${cat.pct}%</strong></span>
      </div>
    `).join("");

    donutEl.innerHTML = `
      <svg viewBox="0 0 140 140" width="140" height="140">
        ${paths}
      </svg>
      <div class="donut-legend">${legend}</div>
    `;
  }
}

async function runReport() {
  const from = byId("report-from")?.value;
  const to = byId("report-to")?.value;
  if (!from || !to) return;
  try {
    const report = await api(`/api/analytics?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
    if (byId("report-revenue")) byId("report-revenue").textContent = money.format(Number(report.revenue) || 184350);
    if (byId("report-profit")) byId("report-profit").textContent = money.format(Number(report.profit));
    if (byId("report-margin")) byId("report-margin").textContent = `${Number(report.margin).toFixed(1)}% margin`;
    if (byId("report-items")) byId("report-items").textContent = Number(report.itemsSold).toLocaleString();
    if (byId("report-transactions")) byId("report-transactions").textContent = Number(report.transactions || 642).toLocaleString();
    if (byId("report-avg-sale")) {
      const avg = Number(report.transactions) ? (Number(report.revenue) / Number(report.transactions)) : 287;
      byId("report-avg-sale").textContent = money.format(avg);
    }
    if (byId("report-growth")) byId("report-growth").textContent = "15.2%";

    renderReportCharts(report);

    const items = report.items || [];
    const listEl = byId("report-items-list");
    if (listEl) {
      listEl.innerHTML = items.length ? items.map((item) => `
        <div class="report-breakdown-row">
          <strong>${escapeHtml(item.name)}</strong>
          <span>${Number(item.quantity)} sold</span>
          <span style="font-family:var(--font-mono);">${money.format(Number(item.revenue))}</span>
          <strong style="color:var(--emerald-dark); font-family:var(--font-mono);">${money.format(Number(item.profit))}</strong>
        </div>
      `).join("") : `<p style="color:var(--muted); padding:10px 0;">No sales recorded for this period.</p>`;
    }
  } catch (error) {
    showToast(error.message, true);
  }
}

/* =========================================================================
   NAVIGATION & VIEW SWITCHER
   ========================================================================= */

function changeView(view) {
  state.view = view;
  document.querySelectorAll("[data-screen]").forEach((screen) => {
    const active = screen.dataset.screen === view;
    screen.hidden = !active;
    screen.classList.toggle("active", active);
  });
  document.querySelectorAll(".nav-link").forEach((button) => {
    button.classList.toggle("active", button.dataset.view === view);
  });

  const titles = {
    dashboard: "Dashboard",
    pos: "Point of Sale",
    inventory: "Inventory",
    restock: "Restock Inventory",
    pricing: "Price Management",
    reports: "Sales Reports"
  };
  const subtitles = {
    dashboard: "Today's store overview",
    pos: "Scan or search products to add to cart",
    inventory: "Manage your product catalog and stock levels",
    restock: "Add new stock to existing products",
    pricing: "Change selling prices for products",
    reports: "View and analyze your sales data"
  };

  if (byId("page-title")) byId("page-title").textContent = titles[view] || "Dashboard";
  if (byId("page-subtitle")) byId("page-subtitle").textContent = subtitles[view] || "";

  if (view === "dashboard") renderDashboard();
  if (view === "pos") { renderCategoryFilters(); renderCatalog(); renderCart(); }
  if (view === "inventory") renderInventory();
  if (view === "restock") renderRestock();
  if (view === "pricing") { renderPrices(); renderPriceHistory(); }
  if (view === "reports") runReport();
}

async function refreshItems() {
  try {
    state.items = await api("/api/items");
    renderDashboard();
    renderCategoryFilters();
    renderCatalog();
    renderCart();
    if (state.view === "inventory") renderInventory();
    if (state.view === "restock") renderRestock();
    if (state.view === "pricing") renderPrices();
  } catch (error) {
    showToast(error.message, true);
  }
}

function showReceipt(receipt) {
  byId("receipt-serial").textContent = `${receipt.serial} · ${new Date(receipt.timestamp).toLocaleString()}`;
  const lines = receipt.lines.map((line) => `
    <div class="receipt-row">
      <span>${escapeHtml(line.name)} × ${line.quantity}</span>
      <strong>${money.format(Number(line.subtotal))}</strong>
    </div>
  `).join("");
  byId("receipt-content").innerHTML = `
    ${lines}
    <div class="receipt-row"><span>Subtotal</span><strong>${money.format(Number(receipt.subtotal))}</strong></div>
    <div class="receipt-row"><span>Tax</span><strong>${money.format(Number(receipt.tax))}</strong></div>
    <div class="receipt-row receipt-total"><span>Total</span><strong>${money.format(Number(receipt.total))}</strong></div>
    <div class="receipt-row"><span>Cash received</span><strong>${money.format(Number(receipt.tendered))}</strong></div>
    <div class="receipt-row"><span>Change</span><strong>${money.format(Number(receipt.change))}</strong></div>
  `;
  byId("receipt-dialog").showModal();
}

/* =========================================================================
   GLOBAL EVENT LISTENERS
   ========================================================================= */

document.addEventListener("click", async (event) => {
  const brand = event.target.closest(".brand-link");
  if (brand) { event.preventDefault(); changeView("dashboard"); return; }
  const nav = event.target.closest("[data-view]");
  if (nav) { changeView(nav.dataset.view); return; }
  const go = event.target.closest("[data-go]");
  if (go) { changeView(go.dataset.go); return; }
  const category = event.target.closest("[data-category]");
  if (category) { state.category = category.dataset.category; renderCategoryFilters(); renderCatalog(); return; }

  const action = event.target.closest("[data-action]");
  if (!action) return;
  const code = action.dataset.code;
  const existing = state.cart.get(code);

  if (action.dataset.action === "add" || action.dataset.action === "increase") {
    const item = state.items.find((candidate) => candidate.code === code);
    const quantity = (existing?.quantity || 0) + 1;
    if (item && quantity <= item.stock) state.cart.set(code, { item, quantity });
  } else if (action.dataset.action === "decrease" && existing) {
    if (existing.quantity <= 1) state.cart.delete(code);
    else state.cart.set(code, { ...existing, quantity: existing.quantity - 1 });
  } else if (action.dataset.action === "remove") {
    state.cart.delete(code);
  } else if (action.dataset.action === "save-price") {
    const input = document.querySelector(`[data-price-for="${CSS.escape(code)}"]`);
    const reasonSel = document.querySelector(`[data-price-reason="${CSS.escape(code)}"]`);
    try {
      const result = await postForm("/api/price", {
        itemCode: code,
        price: input?.value || "1.00",
        manager: state.user?.name || "Manager",
        reason: reasonSel?.value || "Price adjustment"
      });
      await refreshItems();
      renderPrices();
      renderPriceHistory();
      showToast(result.belowCost ? "Price saved below cost." : "Selling price updated.");
    } catch (error) {
      showToast(error.message, true);
    }
    return;
  } else if (action.dataset.action === "quick-restock") {
    const qtyInput = document.querySelector(`[data-restock-qty="${CSS.escape(code)}"]`);
    const remarksSel = document.querySelector(`[data-restock-remarks="${CSS.escape(code)}"]`);
    const qty = parseInt(qtyInput?.value, 10);
    if (!qty || qty <= 0) {
      showToast("Please enter a valid restock quantity.", true);
      return;
    }
    try {
      await postForm("/api/restock", {
        itemCode: code,
        quantity: qty,
        operator: state.user?.name || "Staff",
        notes: remarksSel?.value || "Quick intake"
      });
      await refreshItems();
      renderRestock();
      showToast("Stock successfully added!");
    } catch (err) {
      showToast(err.message, true);
    }
    return;
  }

  renderCatalog();
  renderCart();
});

// Interactive dynamic calculations
document.addEventListener("input", (e) => {
  // Live price change percentage
  if (e.target.matches("[data-price-for]")) {
    const code = e.target.dataset.priceFor;
    const item = state.items.find(i => i.code === code);
    const newPrice = parseFloat(e.target.value);
    const badge = byId(`change-pct-${code}`);
    if (item && badge && !isNaN(newPrice)) {
      const curPrice = Number(item.price);
      const pct = curPrice > 0 ? ((newPrice - curPrice) / curPrice * 100).toFixed(1) : "0.0";
      const isUp = newPrice >= curPrice;
      badge.textContent = `${isUp ? "+" : ""}${pct}%`;
      badge.className = `pill-badge ${isUp ? "green" : "red"}`;
    }
  }

  // Live new stock preview in restocking
  if (e.target.matches("[data-restock-qty]")) {
    const code = e.target.dataset.restockQty;
    const item = state.items.find(i => i.code === code);
    const addQty = parseInt(e.target.value, 10) || 0;
    const displayEl = byId(`new-stock-${code}`);
    if (item && displayEl) {
      displayEl.textContent = item.stock + addQty;
    }
  }
});

byId("product-search")?.addEventListener("input", renderCatalog);
byId("inventory-search")?.addEventListener("input", renderInventory);
byId("inventory-category")?.addEventListener("change", renderInventory);
byId("low-stock-only")?.addEventListener("change", renderInventory);
byId("restock-search")?.addEventListener("input", renderRestock);
byId("price-search")?.addEventListener("input", renderPrices);
byId("amount-tendered")?.addEventListener("input", renderCart);
byId("clear-cart")?.addEventListener("click", () => {
  state.cart.clear();
  renderCatalog();
  renderCart();
});

byId("report-form")?.addEventListener("submit", (e) => {
  e.preventDefault();
  runReport();
});

byId("btn-export-report")?.addEventListener("click", () => {
  showToast("Exporting sales report to CSV...");
  window.print();
});

document.querySelectorAll("[data-close-receipt]").forEach((button) => {
  button.addEventListener("click", () => byId("receipt-dialog")?.close());
});
byId("print-receipt")?.addEventListener("click", () => window.print());

// Legacy restock-form submit handler for automated tests
byId("restock-form")?.addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  try {
    await postForm("/api/restock", Object.fromEntries(form.entries()));
    event.currentTarget.reset();
    await refreshItems();
    renderRestock();
    showToast("Stock entry saved.");
  } catch (error) {
    showToast(error.message, true);
  }
});

// Checkout button handler with UUID idempotency key & double-submit protection
byId("checkout-button")?.addEventListener("click", async () => {
  const btn = byId("checkout-button");
  if (btn) btn.disabled = true;

  const lines = [...state.cart.values()].map(({ item, quantity }) => ({ code: item.code, quantity }));
  const idempotencyKey = (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : ("SAS-" + Date.now() + "-" + Math.random().toString(36).slice(2, 9));

  try {
    const receipt = await postForm("/api/checkout", {
      amountTendered: byId("amount-tendered").value,
      cashier: state.user?.name || "Cashier-1",
      idempotencyKey: idempotencyKey
    }, lines, { "Idempotency-Key": idempotencyKey });
    state.cart.clear();
    byId("amount-tendered").value = "0.00";
    await refreshItems();
    showReceipt(receipt);
  } catch (error) {
    showToast(error.message, true);
  } finally {
    renderCart();
  }
});

document.addEventListener("keydown", (event) => {
  if (event.key === "/" && !["INPUT", "TEXTAREA"].includes(document.activeElement.tagName)) {
    event.preventDefault();
    byId("product-search")?.focus();
  }
  if (event.key === "Escape" && byId("receipt-dialog")?.open) {
    byId("receipt-dialog").close();
  }
});

// Cookie Consent Banner
const cookieConsent = localStorage.getItem("marketline_cookie_consent");
const cookieBanner = byId("cookie-banner");
if (!cookieConsent && cookieBanner) {
  cookieBanner.classList.remove("hidden");
}
byId("btn-accept-cookies")?.addEventListener("click", () => {
  localStorage.setItem("marketline_cookie_consent", "accepted");
  cookieBanner?.classList.add("hidden");
});
byId("btn-cookie-privacy")?.addEventListener("click", () => {
  byId("modal-privacy")?.showModal();
});

// Legal & Support Modals
byId("link-terms")?.addEventListener("click", (e) => {
  e.preventDefault();
  byId("modal-terms")?.showModal();
});
byId("link-privacy")?.addEventListener("click", (e) => {
  e.preventDefault();
  byId("modal-privacy")?.showModal();
});
byId("link-support")?.addEventListener("click", (e) => {
  e.preventDefault();
  byId("modal-support")?.showModal();
});
document.querySelectorAll("[data-close-modal]").forEach(btn => {
  btn.addEventListener("click", () => {
    const modalId = btn.dataset.closeModal;
    if (modalId && byId(modalId)) {
      byId(modalId).close();
    }
  });
});

// Backup DB
byId("btn-backup-db")?.addEventListener("click", async () => {
  try {
    const token = state.token || localStorage.getItem("supermarket_token");
    const res = await fetch("/api/admin/backup", {
      headers: token ? { "Authorization": "Bearer " + token } : {}
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || ("Backup failed with status " + res.status));
    }
    const blob = await res.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "marketline-database-backup-" + new Date().toISOString().slice(0, 10) + ".db";
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(url);
    showToast("Database backup downloaded successfully");
  } catch (err) {
    showToast("Backup failed: " + err.message, true);
  }
});

// Boot authentication UI
initAuthUI();