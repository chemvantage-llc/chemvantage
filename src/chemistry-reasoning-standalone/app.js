const QUESTION_URL = "./data/chemistry-reasoning-questions.json";
const CHARACTER_IDS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14, 15];

let questions = [];
let questionSet = [];
let currentIndex = 0;
let optionOrder = [];
let answered = false;
let answers = [];

const els = {
  progress: document.querySelector("#progress"),
  score: document.querySelector("#score"),
  character: document.querySelector("#character"),
  lineA: document.querySelector("#lineA"),
  lineB: document.querySelector("#lineB"),
  options: document.querySelector("#options"),
  feedback: document.querySelector("#feedback"),
  nextBtn: document.querySelector("#nextBtn"),
  setSize: document.querySelector("#setSize"),
  newSetBtn: document.querySelector("#newSetBtn"),
};

function shuffle(values) {
  const result = [...values];
  for (let i = result.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}

function randomCharacter() {
  const id = CHARACTER_IDS[Math.floor(Math.random() * CHARACTER_IDS.length)];
  return `./assets/dictionary/${id}.gif`;
}

function normalizeQuestion(question) {
  return {
    conversationParts: question.conversationParts || question.question || [],
    options: question.options || [],
    tags: question.tags || [],
  };
}

async function loadQuestions() {
  const response = await fetch(QUESTION_URL);
  if (!response.ok) {
    throw new Error(`Could not load ${QUESTION_URL}`);
  }
  const data = await response.json();
  questions = (Array.isArray(data) ? data : data.questions).map(normalizeQuestion);
}

function setNewOptionOrder() {
  optionOrder = shuffle([0, 1, 2, 3]);
}

function renderQuestion() {
  const question = questionSet[currentIndex];
  answered = answers[currentIndex] !== null;
  els.character.src = randomCharacter();
  els.lineA.textContent = question.conversationParts[0] || "";
  els.lineB.textContent = question.conversationParts[1] || "";
  els.progress.textContent = `Question ${currentIndex + 1} / ${questionSet.length}`;
  els.score.textContent = `Score: ${answers.filter(Boolean).length} / ${answers.filter((answer) => answer !== null).length}`;
  els.feedback.textContent = answered ? feedbackText(answers[currentIndex]) : "";
  els.feedback.className = "feedback";
  if (answered) els.feedback.classList.add(answers[currentIndex] ? "good" : "bad");
  els.nextBtn.textContent = currentIndex === questionSet.length - 1 ? "Finish" : "Next";
  els.nextBtn.disabled = false;
  renderOptions();
}

function renderOptions() {
  const question = questionSet[currentIndex];
  els.options.innerHTML = "";

  optionOrder.forEach((originalIndex) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "option";
    button.dataset.originalIndex = String(originalIndex);
    button.textContent = question.options[originalIndex];
    const marker = document.createElement("span");
    marker.className = "marker";
    marker.setAttribute("aria-hidden", "true");
    const text = document.createElement("span");
    text.textContent = question.options[originalIndex] || "";
    button.textContent = "";
    button.append(marker, text);

    if (answered) {
      button.disabled = true;
      if (originalIndex === 0) button.classList.add("correct");
      if (answers[currentIndex] === false && originalIndex === question.selectedIndex) button.classList.add("wrong");
    }

    button.addEventListener("click", () => chooseOption(button, originalIndex));
    els.options.appendChild(button);
  });
}

function feedbackText(isCorrect) {
  return isCorrect
    ? "Correct. That conclusion is supported by the conversation."
    : "Not quite. The supported conclusion is highlighted.";
}

function chooseOption(button, originalIndex) {
  if (answered) return;
  answered = true;
  const isCorrect = originalIndex === 0;
  answers[currentIndex] = isCorrect;
  questionSet[currentIndex].selectedIndex = originalIndex;

  const buttons = [...els.options.querySelectorAll(".option")];
  buttons.forEach((item) => {
    item.disabled = true;
    if (item.dataset.originalIndex === "0") {
      item.classList.add("correct");
    }
  });

  if (isCorrect) {
    els.feedback.textContent = feedbackText(true);
    els.feedback.classList.add("good");
  } else {
    button.classList.add("wrong");
    els.feedback.textContent = feedbackText(false);
    els.feedback.classList.add("bad");
  }

  els.score.textContent = `Score: ${answers.filter(Boolean).length} / ${answers.filter((answer) => answer !== null).length}`;
}

function goToQuestion(index) {
  currentIndex = Math.max(0, Math.min(index, questionSet.length - 1));
  renderQuestion();
}

function startRandomSet() {
  const requestedSize = Number.parseInt(els.setSize.value, 10);
  const size = Math.min(requestedSize, questions.length);
  questionSet = shuffle(questions).slice(0, size).map((question) => ({ ...question }));
  answers = Array(questionSet.length).fill(null);
  currentIndex = 0;
  setNewOptionOrder();
  renderQuestion();
}

els.nextBtn.addEventListener("click", () => {
  if (currentIndex === questionSet.length - 1) {
    els.feedback.textContent = `Set complete. Final score: ${answers.filter(Boolean).length} / ${questionSet.length}. Choose New Random Set to practice again.`;
    els.feedback.className = "feedback good";
    els.nextBtn.disabled = true;
    return;
  }
  setNewOptionOrder();
  goToQuestion(currentIndex + 1);
});
els.newSetBtn.addEventListener("click", startRandomSet);

loadQuestions()
  .then(startRandomSet)
  .catch((error) => {
    els.progress.textContent = "Could not load questions";
    els.lineA.textContent = error.message;
  });
