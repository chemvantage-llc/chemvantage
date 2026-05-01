// Detect if page is in an iframe and add padding if it's not
if (window===window.top) {
    document.body.classList.add('has-padding');
}
