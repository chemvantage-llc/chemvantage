(() => {
  const quizForm = document.getElementById("quizForm");
  if (!quizForm) return;

  quizForm.addEventListener("submit", (event) => {
    if (quizForm.dataset.timeoutSubmit === "true") return;
    if (!confirmSubmission()) event.preventDefault();
  });

  document.querySelectorAll(".toggle-timer-link").forEach((link) => {
    link.addEventListener("click", (event) => {
      event.preventDefault();
      toggleTimers();
    });
  });

  window.timesUp = () => {
    quizForm.dataset.timeoutSubmit = "true";
    quizForm.requestSubmit();
  };

  const timerMillis = Number(quizForm.dataset.timerMillis);
  if (Number.isFinite(timerMillis)) startTimers(timerMillis);
})();