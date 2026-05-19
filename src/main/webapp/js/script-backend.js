function toggleTimers() {
	var timer0 = document.getElementById('timer0');
	var timer1 = document.getElementById('timer1');
	var ctrl0 = document.getElementById('ctrl0');
	var ctrl1 = document.getElementById('ctrl1');
	if (timer0.style.display=='') {
		timer0.style.display='none';timer1.style.display='none';
		ctrl0.innerHTML='<a href="#" class="toggle-timer-link">show timers</a><p>';
		ctrl1.innerHTML='<a href="#" class="toggle-timer-link">show timers</a><p>';
		document.querySelectorAll('.toggle-timer-link').forEach(link => {
			link.addEventListener('click', function(e) { e.preventDefault(); toggleTimers(); });
		});
	} else {
		timer0.style.display='';
		timer1.style.display='';
		ctrl0.innerHTML='<a href="#" class="toggle-timer-link">hide timers</a><p>';
		ctrl1.innerHTML='<a href="#" class="toggle-timer-link">hide timers</a><p>';
		document.querySelectorAll('.toggle-timer-link').forEach(link => {
			link.addEventListener('click', function(e) { e.preventDefault(); toggleTimers(); });
		});
	}
}
var endMillis;
function countdown() {
	var seconds;
    var minutes;
    var oddSeconds;
    var nowMillis = new Date().getTime();
    var seconds = endMillis<nowMillis?0:Math.round((endMillis-nowMillis)/1000);
	var minutes = seconds<0?0:Math.floor(seconds/60.);
	var oddSeconds = seconds%60;
	for (i=0;i<2;i++) try {  // change the display of timer0 and/or timer1 in the parent page
		    document.getElementById('timer'+i).innerHTML='Time remaining: ' + (minutes==0?'':minutes + (minutes==1?' minute ':' minutes ')) + oddSeconds + (seconds==0?'':' seconds.');}
		catch(Exception){}
    if (seconds <= 0) try { timesUp() } catch (Exception) {} // run a custom script function in the parent page
	else setTimeout(countdown, 1000);
}
function startTimers(m) {  // m is the start value of the timer in milliseconds 
	endMillis = new Date().getTime() + m; // endMillis is the absolute time of the user's System clock at the end of the tie interval
	countdown();
}
function confirmSubmission() {
	var elements = document.getElementById('quizForm').elements;
	var nQuestions = 0;
  var nAnswers = 0;
	var i;
	var checkboxes;
	var lastCheckboxIndex;
	for (i=0;i<elements.length;i++) {
		if (isNaN(elements[i].name)) continue;
		if (elements[i].type=='text') {
			nQuestions++;
			if (elements[i].value.length>0) nAnswers++;
		}
		else if (elements[i].type=='radio') {
			nQuestions++;
			let radioButtons = document.getElementsByName(elements[i].name);
			let lastRadioIndex = i + radioButtons.length - 1;
			for (let k=0; k<radioButtons.length; k++) {
				if (radioButtons[k].checked) {
					nAnswers++;
					break;
				}
			}
			i = lastRadioIndex;
		}
		else if (elements[i].type=='checkbox') {
			nQuestions++;
			checkboxes = document.getElementsByName(elements[i].name);
			lastCheckboxIndex = i + checkboxes.length - 1;
			for (j=0;j<checkboxes.length;j++) {
        if (checkboxes[j].checked==true) {
				  nAnswers++;
				  break;
			  }    
		  }  
	    i = lastCheckboxIndex;
		}  
  }
	if (nAnswers<nQuestions) return confirm('Submit this quiz for scoring now? ' + (nQuestions-nAnswers) + ' answer' + ((nQuestions-nAnswers)==1?'':'s') + ' may be left blank.');
	else return true;
}

function ajaxSubmit(url,id,params,studentAnswer,note,email) {
  var xmlhttp;
  if (url.length==0) return false;
  xmlhttp=GetXmlHttpObject();
  if (xmlhttp==null) {
    alert ('Sorry, your browser does not support AJAX!');
    return false;
  }
  xmlhttp.onreadystatechange=function() {
    if (xmlhttp.readyState==4) {
      document.getElementById('feedback' + id).innerHTML=
      '<FONT COLOR=#EE0000><b>Thank you. An editor will review your comment.</b></FONT><p>';
    }
  }
  url += '&QuestionId=' + id + '&Params=' + params + '&Notes=' + note + '&Email=' + email + '&StudentAnswer=' + studentAnswer;
  xmlhttp.open('GET',url,true);
  xmlhttp.send(null);
  return false;
}
function synchronizeScore(forUserId,sig,path) {
  let xmlhttp=GetXmlHttpObject();
  let url = path + '?UserRequest=SynchronizeScore&sig=' + sig + '&ForUserId=' + forUserId;
  if (xmlhttp==null) {
    alert ('Sorry, your browser does not support AJAX!');
    return false;
  }
  xmlhttp.onreadystatechange=function() {
    if (xmlhttp.readyState==4) {
      if (xmlhttp.responseText.includes('OK')) {
        document.getElementById('cell'+forUserId).innerHTML='OK. Check grade book settings.';
        setTimeout(() => {location.reload();}, 500);
      } else {
        document.getElementById('cell'+forUserId).innerHTML=xmlhttp.responseText;
      }
    }
  }
  xmlhttp.open('GET',url,true);
  xmlhttp.send(null);
  return false;
}
function GetXmlHttpObject() {
  if (window.XMLHttpRequest) { // code for IE7+, Firefox, Chrome, Opera, Safari
    return new XMLHttpRequest();
  }
  if (window.ActiveXObject) { // code for IE6, IE5
    return new ActiveXObject('Microsoft.XMLHTTP');
  }
  return null;
}

function waitForSage(buttonId) {
  let b = document.getElementById(buttonId);
  b.disabled = true;
  b.innnerHTML = 'Please wait a moment..';
}

function waitforSync() {
	let b = document.getElementById('syncAll');
	b.disabled = true;
	b.value = 'Please wait while we recalculate all scores.';
}
function waitForRetryScore() {
 let b = document.getElementById('RetryButton');
 b.disabled = true;
 b.value = 'Please wait a moment while we score your response.';
}

function waitForScore(qid) {
 let b = document.getElementById('sub' + qid);
 b.disabled = true;
 b.value = 'Please wait a moment while we score your response.';
}

function synchTimer(sig) {
  var xmlhttp=new XMLHttpRequest();
  if (xmlhttp==null) {
    alert ('Sorry, your browser does not support AJAX!');
    return false;
  }
  xmlhttp.onreadystatechange=function() {
    if (xmlhttp.readyState==4) {
     const serverNowMillis = xmlhttp.responseText.trim();  // server returned new Date().getTime()
     endMillis += Date.now() - serverNowMillis;          // corrects for fast or slow browser clock
    }
  }
  var url = 'Poll?UserRequest=Synch&sig=' + sig;
  timer0.innerHTML = 'synchronizing clocks...';
  xmlhttp.open('GET',url,true);
  xmlhttp.send(null);
  return false;
}
				
