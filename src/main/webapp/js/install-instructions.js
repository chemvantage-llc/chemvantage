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
