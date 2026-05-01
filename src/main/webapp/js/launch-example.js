// Fetch and open example with signature
function launch(myLink) {
  fetch('/examples')
    .then(response => response.json())
    .then(data => {
      myLink += '?sig=' + data.sig;
      window.open(myLink, '_blank');
    })
    .catch(error => console.error('Error fetching data:', error));
}

// Attach event listeners to launch buttons when DOM is ready
document.addEventListener('DOMContentLoaded', function() {
  var homeworkBtn = document.getElementById('launch-homework');
  var quizBtn = document.getElementById('launch-quiz');
  var videoBtn = document.getElementById('launch-video');
  
  if (homeworkBtn) homeworkBtn.addEventListener('click', function() { launch('/Homework'); });
  if (quizBtn) quizBtn.addEventListener('click', function() { launch('/Quiz'); });
  if (videoBtn) videoBtn.addEventListener('click', function() { launch('/VideoQuiz'); });
});
