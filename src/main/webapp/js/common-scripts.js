// Prevent indexing of dev/localhost instances
if (window.location.hostname.includes('appspot.com') || window.location.hostname === 'localhost') {
  var meta = document.createElement('meta');
  meta.name = 'robots';
  meta.content = 'noindex, nofollow';
  document.head.appendChild(meta);
}

// Update footer year to current year
(function () {
  var y = new Date().getFullYear();
  var el = document.getElementById('year');
  if (el) el.textContent = y;
})();

// Launch examples in a new window with signature
function launch(myLink) {
  fetch('/examples')
    .then(response => response.json())
    .then(data => {
      myLink += '?sig=' + data.sig;
      window.open(myLink, '_blank');
    })
    .catch(error => console.error('Error fetching data:', error));
}
