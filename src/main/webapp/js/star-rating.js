// Global state for star ratings - stores fixed state for each question ID
window.starRatingState = window.starRatingState || {};

// Generic function to handle star rating display
function showStarsRating(elementId, nStars, clicked) {
    if (clicked === undefined) clicked = false;
    
    // Initialize state if not exists
    if (window.starRatingState[elementId] === undefined) {
        window.starRatingState[elementId] = false;
    }
    
    var fixed = window.starRatingState[elementId];
    
    if (fixed && !clicked) return;
    
    var voteElement = document.getElementById('vote' + elementId);
    if (voteElement) {
        voteElement.innerHTML = (nStars === 0 ? '(click a star)' : nStars + (nStars > 1 ? ' stars' : ' star'));
    }
    
    for (let i = 1; i < 6; i++) {
        var starElement = document.getElementById('star' + i + elementId);
        if (starElement) {
            starElement.src = (nStars < i ? 'https://images.chemvantage.org/star1.gif' : 'https://images.chemvantage.org/star2.gif');
        }
    }
    
    window.starRatingState[elementId] = clicked;
    
    var answerElement = document.getElementById(String(elementId));
    if (answerElement && clicked) {
        answerElement.value = nStars;
    }
}
