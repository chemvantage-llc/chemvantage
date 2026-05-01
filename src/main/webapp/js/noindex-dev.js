// Prevent dev site indexing
if (window.location.hostname.includes('appspot.com') || window.location.hostname === 'localhost') {
  var meta = document.createElement('meta');
  meta.name = 'robots';
  meta.content = 'noindex, nofollow';
  document.head.appendChild(meta);
}
