// Toggle visibility of LMS-specific installation instructions
function showInstructions(lmsId) {
  var allInstructions = document.getElementsByClassName('lms-instructions');
  // Hide all sections
  for (var i = 0; i < allInstructions.length; i++) {
    allInstructions[i].style.display = 'none';
  }
  // Show the selected section
  var selectedInstructions = document.getElementById(lmsId);
  if (selectedInstructions) {
    selectedInstructions.style.display = 'block';
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
});
