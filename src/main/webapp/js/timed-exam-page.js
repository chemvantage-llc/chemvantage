(() => {
  const examForm = document.querySelector("form[data-timed-exam-form]");
  if (!examForm) return;

  examForm.addEventListener("submit", (event) => {
    if (examForm.dataset.timeoutSubmit === "true") return;
    if (!confirm(examForm.dataset.submitConfirmation)) event.preventDefault();
  });

  document.querySelectorAll(".toggle-timer-link").forEach((link) => {
    link.addEventListener("click", (event) => {
      event.preventDefault();
      toggleTimers();
    });
  });

  document.querySelectorAll("div[id^='showWork']").forEach((showWork) => {
    showWork.style.display = "";
  });

  window.timesUp = () => {
    examForm.dataset.timeoutSubmit = "true";
    examForm.requestSubmit();
  };

  const timerMillis = Number(examForm.dataset.timerMillis);
  if (Number.isFinite(timerMillis)) startTimers(timerMillis);
})();