(function() {
  function normalize(value) {
    return (value || '').toLowerCase().replace(/[^a-z0-9]+/g, '');
  }

  function fuzzyMatch(needle, haystack) {
    if (!needle) return true;
    if (haystack.indexOf(needle) >= 0) return true;
    var index = 0;
    for (var i = 0; i < haystack.length && index < needle.length; i++) {
      if (haystack.charAt(i) === needle.charAt(index)) index++;
    }
    return index === needle.length;
  }

  var lmsButtons = Array.prototype.slice.call(document.querySelectorAll('[data-lms-filter]'));
  var lmsCards = Array.prototype.slice.call(document.querySelectorAll('[data-lms-video]'));
  var lmsEmpty = document.getElementById('lms-filter-empty');
  var activeLms = '';

  function applyLmsFilter(nextFilter) {
    activeLms = activeLms === nextFilter ? '' : nextFilter;
    var visibleCount = 0;
    lmsCards.forEach(function(card) {
      var visible = !activeLms || card.getAttribute('data-lms') === activeLms;
      card.hidden = !visible;
      if (visible) visibleCount++;
    });
    lmsButtons.forEach(function(button) {
      button.setAttribute('aria-pressed', button.getAttribute('data-lms-filter') === activeLms ? 'true' : 'false');
    });
    if (lmsEmpty) lmsEmpty.hidden = visibleCount !== 0;
  }

  lmsButtons.forEach(function(button) {
    button.addEventListener('click', function() {
      applyLmsFilter(button.getAttribute('data-lms-filter'));
    });
  });

  var topicSearch = document.getElementById('topic-video-search');
  var topicCount = document.getElementById('topic-video-count');
  var topicEmpty = document.getElementById('topic-video-empty');
  var topicCards = Array.prototype.slice.call(document.querySelectorAll('[data-topic-video]'));

  function applyTopicSearch() {
    var query = normalize(topicSearch ? topicSearch.value : '');
    var visibleCount = 0;
    topicCards.forEach(function(card) {
      var title = normalize(card.getAttribute('data-video-title') || card.textContent);
      var visible = fuzzyMatch(query, title);
      card.hidden = !visible;
      if (visible) visibleCount++;
    });
    if (topicCount) topicCount.textContent = visibleCount + (visibleCount === 1 ? ' video' : ' videos');
    if (topicEmpty) topicEmpty.hidden = visibleCount !== 0;
  }

  if (topicSearch) {
    topicSearch.addEventListener('input', applyTopicSearch);
    applyTopicSearch();
  }
})();
