// Toggle visibility of LMS-specific installation instructions
function showInstructions(lmsId) {
  var allInstructions = document.getElementsByClassName('lms-instructions');
  // Hide all sections
  for (var i = 0; i < allInstructions.length; i++) {
    allInstructions[i].style.display = 'none';
  }
  var buttons = document.querySelectorAll('[data-instruction-type]');
  buttons.forEach(function(button) {
    var isActive = button.getAttribute('data-instruction-type') === lmsId;
    button.classList.toggle('active', isActive);
    button.setAttribute('aria-pressed', String(isActive));
  });
  // Show the selected section
  var selectedInstructions = document.getElementById(lmsId);
  if (selectedInstructions) {
    selectedInstructions.style.display = 'block';
    selectedInstructions.scrollIntoView({ block: 'start' });
  }
}

// Initialize event delegation for LMS instruction buttons using data attributes
document.addEventListener('DOMContentLoaded', function() {
  var buttons = document.querySelectorAll('[data-instruction-type]');
  buttons.forEach(function(button) {
    button.addEventListener('click', function() {
      var instructionType = this.getAttribute('data-instruction-type');
      showInstructions(instructionType);
    });
  });
  var params = new URLSearchParams(window.location.search);
  var requestedLms = params.get('lms') || window.location.hash.replace('#', '');
  if (requestedLms && document.getElementById(requestedLms)) {
    showInstructions(requestedLms);
  }
});
