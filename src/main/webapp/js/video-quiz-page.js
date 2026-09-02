(() => {
  const page = document.getElementById("videoQuizPage");
  if (!page) return;

  const quiz = document.getElementById("quiz_div");
  const video = document.getElementById("video_div");
  const videoId = page.dataset.videoId;
  const videoSerialNumber = page.dataset.videoSerialNumber;
  const signature = page.dataset.signature;
  let segment = Number(page.dataset.segment);
  const breaks = JSON.parse(page.dataset.breaks || "[]");
  let start = Number(page.dataset.start);
  let end = Number(page.dataset.end);
  let player;

  window.showWorkBox = () => {};
  window.onYouTubeIframeAPIReady = () => {
    player = new YT.Player("video_div", {
      height: "315",
      width: "560",
      videoId: videoSerialNumber,
      playerVars: {
        enablejsapi: 1,
        autoplay: 0,
        start,
        end,
        modestbranding: 1,
        origin: window.location.origin,
      },
      events: { onReady: onPlayerReady, onStateChange: onPlayerStateChange },
    });
  };

  function onPlayerReady() {
    start = segment === 0 ? 0 : breaks[segment - 1];
    end = breaks.length <= segment ? -1 : breaks[segment];
    player.loadVideoById({ videoId: videoSerialNumber, startSeconds: start, endSeconds: end });
    loadQuizlet();
  }

  function onPlayerStateChange(event) {
    if (event.data === YT.PlayerState.ENDED) {
      const fullscreen = document.fullscreenElement || document.webkitFullscreenElement || document.mozFullScreenElement || document.msFullscreenElement;
      if (fullscreen && document.exitFullscreen) document.exitFullscreen().catch(() => {});
      video.style.display = "none";
      quiz.style.display = "";
    } else if (event.data === YT.PlayerState.PLAYING) {
      video.style.display = "";
      quiz.style.display = "none";
    }
  }

  function loadQuizlet() {
    quiz.textContent = "Loading questions...";
    fetch(`/VideoQuiz?VideoId=${encodeURIComponent(videoId)}&UserRequest=ShowQuizlet&Segment=${encodeURIComponent(segment)}&sig=${encodeURIComponent(signature)}`)
      .then((response) => response.text())
      .then((html) => {
        quiz.innerHTML = html;
        bindQuizletForm();
      })
      .catch((error) => { quiz.textContent = error.message; });
  }

  function bindQuizletForm() {
    const quizlet = document.getElementById("quizlet");
    if (!quizlet) return;
    quizlet.addEventListener("submit", (event) => {
      event.preventDefault();
      const submitButton = document.getElementById("submitButton");
      if (submitButton) submitButton.disabled = true;
      submitQuizlet(quizlet);
    });
  }

  function submitQuizlet(quizlet) {
    fetch("/VideoQuiz", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(new FormData(quizlet)),
    })
      .then((response) => response.text())
      .then((html) => {
        quiz.style.display = start >= 0 ? "none" : "";
        quiz.innerHTML = html;
        segment++;
        start = breaks[segment - 1];
        end = breaks.length > segment ? breaks[segment] : -1;
        if (start >= 0 && player) player.loadVideoById({ videoId: videoSerialNumber, startSeconds: start, endSeconds: end });
        bindQuizletForm();
      })
      .catch((error) => { quiz.textContent = error.message; });
  }

  const youtubeApi = document.createElement("script");
  youtubeApi.src = "https://www.youtube.com/iframe_api";
  document.head.appendChild(youtubeApi);
})();