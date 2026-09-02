function getTopics() {
  /* Make an HTTP request for <select> form element with all topics */
  const xhttp = new XMLHttpRequest();
  xhttp.onload = function() {
	var selector = document.getElementById("topic");
	var options = this.responseText.split("|");
	for (const [index, a] of options.entries()) {
  const opt = document.createElement('option');
  		opt.value = index;
    opt.textContent = a;
  		selector.appendChild(opt);
	}
  }
  xhttp.open("GET", "/itembank?r=topics", true);
  xhttp.send();
}
getTopics();
function getItems() {
  /* Make an HTTP request for sample question items */
  const xhttp = new XMLHttpRequest();
  var topicSelector = document.getElementById("topic");
  var assignmentTypeSelector = document.getElementById("type");
  var viewer = document.getElementById("itemViewBox");
  xhttp.onload = function() {
	viewer.innerHTML = this.responseText;
  }
  xhttp.open("GET", "/itembank?r=items&Topic=" + topicSelector.value + "&Type=" + assignmentTypeSelector.value, true);
  xhttp.send();
  return false;
}
