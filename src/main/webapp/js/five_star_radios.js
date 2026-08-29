var star1 = new Image();
var star2 = new Image();
star1.src = '/images/star1.gif';
star2.src = '/images/star2.gif';
var starRadioButtons = document.querySelectorAll("input[name='StarSelection']");
var voteSpan = document.getElementById('vote');
function showStars(n) {
  for (i = 1; i < 6; i++) document.getElementById(i).src = (i<=n?star2.src:star1.src);
  if (voteSpan==null) return;
  voteSpan.style.display='inline';
  voteSpan.innerHTML = (n==0?'&nbsp;&nbsp;(click a star)':'&nbsp;&nbsp;' + n + (n>1?' stars':' star'));
}
async function submitStars(event,sig) {
  if (event.type==='keydown' && !(event.key===' ' || event.key==='Enter')) return;
  const selectedValue = document.querySelector(`input[name="StarSelection"]:checked`).value;
  try {
	var url = '/Feedback?UserRequest=AjaxRating&NStars=' + selectedValue + '&sig=' + sig;
	const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
	const resultString = await response.text();
	document.getElementById('star-rating').innerHTML = resultString;
    return;
  } catch (error) {
    document.getElementById('star-rating').innerHTML = 'Error recording rating: ' + error.message;
	return;
  }
}
