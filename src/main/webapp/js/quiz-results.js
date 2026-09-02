(() => {
  document.querySelectorAll("[data-quiz-show-answers]").forEach((link) => {
    link.addEventListener("click", (event) => {
      event.preventDefault();
      link.style.display = "none";
      const answers = document.getElementById(link.dataset.quizShowAnswers);
      if (answers) answers.style.display = "inline";
    });
  });
})();