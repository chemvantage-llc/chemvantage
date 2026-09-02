(() => {
  document.querySelectorAll("[data-sage-auto-redirect]").forEach((element) => {
    const delay = Number(element.dataset.sageRedirectDelay || 0);
    if (delay > 0) setTimeout(() => window.location.replace(element.dataset.sageAutoRedirect), delay);
  });

  const askButton = document.getElementById("askButton");
  const askForm = document.getElementById("askForm");
  if (askButton && askForm) askButton.addEventListener("click", () => {
    askButton.style.display = "none";
    askForm.style.display = "inline";
  });

  document.querySelectorAll("form[data-sage-ask-form]").forEach((form) => form.addEventListener("submit", () => {
    const button = document.getElementById("ask");
    if (button) {
      button.disabled = true;
      button.value = "Please wait a moment for Sage to answer.";
    }
  }));

  document.querySelectorAll("[data-sage-wait-label]").forEach((link) => link.addEventListener("click", () => {
    link.textContent = link.dataset.sageWaitLabel;
  }));

  document.querySelectorAll("[data-sage-show-answer]").forEach((link) => link.addEventListener("click", (event) => {
    event.preventDefault();
    link.style.display = "none";
    const answer = document.getElementById(link.dataset.sageShowAnswer);
    if (answer) answer.style.display = "inline";
  }));

  document.querySelectorAll("[data-sage-explanation-url]").forEach((button) => button.addEventListener("click", () => {
    const explanation = document.getElementById("explanation");
    button.disabled = true;
    button.textContent = "Please wait a moment for Sage to respond.";
    fetch(button.dataset.sageExplanationUrl)
      .then((response) => response.text())
      .then((html) => {
        if (explanation) explanation.innerHTML = html;
        if (!document.getElementById("Mathjax-script")) {
          const mathjax = document.createElement("script");
          mathjax.id = "Mathjax-script";
          mathjax.src = "https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js";
          document.head.appendChild(mathjax);
        }
      })
      .catch((error) => {
        if (explanation) explanation.textContent = error.message;
      });
  }));

  document.querySelectorAll("form[data-sage-score-form]").forEach((form) => form.addEventListener("submit", () => {
    const button = form.querySelector("[data-sage-score-button]");
    if (button) {
      button.disabled = true;
      button.value = "Please wait a moment while we score your response.";
    }
  }));

  document.querySelectorAll("[data-sage-helpful]").forEach((link) => link.addEventListener("click", (event) => {
    event.preventDefault();
    const response = link.dataset.sageHelpful;
    const config = document.querySelector("[data-sage-feedback-url]");
    const helpful = document.getElementById("helpful");
    if (helpful) helpful.textContent = response === "true" ? "Thank you for the feedback." : "Thank you for the feedback. I'll try to do better next time.";
    if (config) fetch(`${config.dataset.sageFeedbackUrl}&Response=${encodeURIComponent(response)}`).catch(() => {});
    if (config && config.dataset.sageRedirect) {
      const delay = Number(config.dataset.sageRedirectDelay || 0);
      setTimeout(() => window.location.replace(config.dataset.sageRedirect), delay);
    }
  }));
})();