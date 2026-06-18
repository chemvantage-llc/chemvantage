(function() {
  var measurementId = 'G-616V3J3MT4';
  var trackedHosts = ['www.chemvantage.org', 'chemvantage.org'];

  if (trackedHosts.indexOf(window.location.hostname) === -1) return;

  window.dataLayer = window.dataLayer || [];
  window.gtag = window.gtag || function() {
    window.dataLayer.push(arguments);
  };

  var script = document.createElement('script');
  script.async = true;
  script.src = 'https://www.googletagmanager.com/gtag/js?id=' + encodeURIComponent(measurementId);
  document.head.appendChild(script);

  window.gtag('js', new Date());
  window.gtag('config', measurementId);
})();
