// Prevent indexing of dev/localhost instances
if (window.location.hostname.includes('appspot.com') || window.location.hostname === 'localhost') {
  var meta = document.createElement('meta');
  meta.name = 'robots';
  meta.content = 'noindex, nofollow';
  document.head.appendChild(meta);
}

// Show/hide LMS-specific installation instructions
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

// Update footer year to current year
(function () {
  var y = new Date().getFullYear();
  var el = document.getElementById('year');
  if (el) el.textContent = y;
})();
