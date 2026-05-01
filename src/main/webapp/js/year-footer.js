// Update footer with current year
(function () {
  var y = new Date().getFullYear();
  var el = document.getElementById('year');
  if (el) el.textContent = y;
})();
