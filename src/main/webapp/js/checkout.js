  var agreeTerms;
  var agreeNoRefunds
  var selectPaymentMethod;
  var payment_div;
  var sig;
  var client_id;
  var platform_deployment_id;
  var price;
  var nmonths;
  
  function showSelectPaymentMethod() {
    agreeTerms = document.getElementById('terms');
    agreeNoRefunds = document.getElementById('norefunds');
    selectPaymentMethod = document.getElementById('select_payment_method');
    if (agreeTerms.checked && agreeNoRefunds.checked) {
      agreeTerms.disabled = true;
      agreeNoRefunds.disabled = true;
      selectPaymentMethod.style = 'display:inline';
      client_id = document.getElementById('client_id').value;
      platform_deployment_id = document.getElementById('platform_deployment_id').value;
      price = document.getElementById('price').value;
      sig = document.getElementById('sig').value;
      payment_div = document.getElementById('payment_div');
    }
   	else selectPaymentMethod.style = 'display:none';
  }
  
  function showVoucherRedemption() {
    document.getElementById('voucher_redemption').style = 'display:inline';
    document.getElementById('subscription_purchase').style = 'display:none';
    var postpone = document.getElementById('postpone_payment');
    if (postpone) postpone.style = 'display:none';
  }
  
  function showSubscriptionPurchase() {
    document.getElementById('voucher_redemption').style = 'display:none';
    document.getElementById('subscription_purchase').style = 'display:inline';
    var postpone = document.getElementById('postpone_payment');
    if (postpone) postpone.style = 'display:none';  
  }

  function extendFreeTrial(sig) {
    fetch("/checkout", {  
      method: "POST",
      headers:{
        "Content-Type": "application/x-www-form-urlencoded"
      },
      body: new URLSearchParams({
        "UserRequest": "ExtendFreeTrial",
        "sig": sig,
      })
    })
    .then (response => {
      if (response.ok) {
        return response.json();
      } else {
        throw new Error('API call failed. Response status code was ' + response.status);
      }
    })
    .then (data => {
      if (data.error) {
        throw new Error(data.error);
      } else {
        selectPaymentMethod.innerHTML = "<h2>Good choice!</h2>You may use ChemVantage free for the next 24 hours and purchase a subscription after that.";
        document.getElementById('proceed').style = "display: inline";
      }
    })
    .catch(error => {
      selectPaymentMethod.textContent = "Sorry, an error occurred. " + error.message;
      console.error(error);
    });
  }

  function logoutUser(sig) {
    fetch("/checkout", {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded"
      },
      body: new URLSearchParams({
        "UserRequest": "Logout",
        "sig": sig
      })
    }).catch(error => console.error('Logout failed:', error));
  }

  function redeemVoucher(sig,platform_deployment_id) {
  	let voucher_code = document.getElementById("voucher_code").value;
  	let proceed = document.getElementById("proceed");
  	if (!voucher_code || voucher_code.length!=6) {
      logoutUser(sig);
  	  selectPaymentMethod.innerHTML = "<h2>Error</h2>"
        + "The code was missing or invalid.<br/>"
        + "<b>You are now logged out of ChemVantage</b>. To proceed, please click the assignment link in your LMS.";
      return null;
  	}
    fetch("/checkout", {
 	  method: "POST",
  	  headers:{
        "Content-Type": "application/x-www-form-urlencoded"
      },
      body: new URLSearchParams({
        "UserRequest": "RedeemVoucher",
        "sig": sig,
        "d": platform_deployment_id,
        "voucher_code": voucher_code
   	  })
  	})
  	.then (response => {
  	  if (response.ok) {
  	    return response.json();
  	  } else {
  	    throw new Error('API call failed. Response status code was ' + response.status);
  	  }
  	})
  	.then (data => {
      if (data.error) {
        throw new Error(data.error);
      }
    selectPaymentMethod.textContent = "Thank you. Your voucher was redeemed successfully. Your subscription expires on " + data.exp;
  	  proceed.style = "display: inline";
  	})
  	.catch(error => {
  	  logoutUser(sig);
      selectPaymentMethod.textContent = "Error. " + error.message
      + " You are now logged out of ChemVantage. To proceed, please click the assignment link in your LMS.";
  	});
  }
  
  function startCheckout() {
    nmonths = document.getElementById('nmonths').value;
    let value = price * (nmonths - Math.floor(nmonths/3));
    selectPaymentMethod.textContent = nmonths + "-month ChemVantage subscription: $" + value + ".00 USD";
    payment_div.style = "display: inline";
  }
   
  window.paypal.Buttons({
      style: {
        shape: 'rect',
        color: 'gold',
        layout: 'vertical',
        label: 'paypal'
      },
      
      createOrder: function(data, actions) {
        return fetch("/checkout", {
          method: "post",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
        	"UserRequest": "CreateOrder",
        	"sig": sig,
        	"d": platform_deployment_id,
        	"nmonths": nmonths
   	  	  })
        })
        .then((response) => response.json())
        .then((order) => {
          return order.id 
        });
      },
      
      onApprove: function(data, actions) {
        let order_id = data.orderID;
        return fetch("/checkout", {
          method: "post",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            "UserRequest": "CompleteOrder",
            "sig": sig,
            "order_id": order_id
          })
        })
        .then((response) => response.json())
        .then((order_details) => {
          // Extract payment amount from the new response structure
          let amount = order_details.payment ? order_details.payment.amount : (price * (nmonths - Math.floor(nmonths/3)));
          let currency = order_details.payment ? order_details.payment.currency : "USD";
          
          selectPaymentMethod.textContent = "Thank you for your purchase: "
            + amount + " " + currency + ". "
            + "Your " + nmonths + "-month ChemVantage subscription is now active and expires on " + order_details.expires + ". "
            + "OrderId: " + order_id + ". Please print a copy of this page for your records.";
          
          document.getElementById('proceed').style = "display: inline";
            
          payment_div.style = "display: none";
        })
        .catch((error) => {
      	  console.log(error);
      	  selectPaymentMethod.innerHTML = "Sorry, an error occurred. Your payment was not completed.";
      	  payment_div.style = "display: none";
        });       
      },
      
      onCancel: function(data) {
      	console.log(data);
        logoutUser(sig);
      	selectPaymentMethod.innerHTML = "<h2>Order canceled.</h2>"
      	  + "To continue, please launch the assignment again in your LMS.";
      	payment_div.style = "display: none";
      },
      
      onError: function(error) {
      	console.log(error);
        logoutUser(sig);
        selectPaymentMethod.innerHTML = "<h2>Error</h2>"
      	  + "To continue, please launch the assignment again in your LMS.";
      	payment_div.style = "display: none";
      },
    })  
  	.render('#paypal-button-container');
