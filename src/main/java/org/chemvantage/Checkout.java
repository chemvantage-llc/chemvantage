/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2024 ChemVantage LLC
*   
*    This program is free software: you can redistribute it and/or modify
*   it under the terms of the GNU General Public License as published by
*   the Free Software Foundation, either version 3 of the License, or
*   (at your option) any later version.
*
*   This program is distributed in the hope that it will be useful,
*   but WITHOUT ANY WARRANTY; without even the implied warranty of
*   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*   GNU General Public License for more details.
*
*   You should have received a copy of the GNU General Public License
*   along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.google.gson.JsonObject;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.paypal.core.PayPalEnvironment;
import com.paypal.core.PayPalHttpClient;
import com.paypal.http.HttpResponse;
import com.paypal.orders.*;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@WebServlet("/checkout")
public class Checkout extends HttpServlet {

	@Serial
	private static final long serialVersionUID = 137L;
	private static int price = 2;
	private static PayPalHttpClient payPalClient = null;
	private static final Long ONE_WEEK = 7L*24*60*60*1000;  // Length of free trial eligibilityperiod
	private static final Long ONE_DAY = 24L*60*60*1000; // Extension period for free trial
	
	/**
	 * Get or create the PayPal HTTP client
	 */
	private static PayPalHttpClient getPayPalClient() {
		if (payPalClient == null) {
			String clientId = Subject.getPayPalClientId();
			String clientSecret = Subject.getPayPalClientSecret();
			PayPalEnvironment environment;
			
			// Use sandbox for dev environment, live for production
			if (Subject.getProjectId().equals("dev-vantage-hrd")) {
				environment = new PayPalEnvironment.Sandbox(clientId, clientSecret);
			} else {
				environment = new PayPalEnvironment.Live(clientId, clientSecret);
			}
			
			payPalClient = new PayPalHttpClient(environment);
		}
		return payPalClient;
	}
	
	public String getServletInfo() {
		return "This servlet is used by students to purchase ChemVantage subscriptions.";
	}

	public void doGet(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		try {
			User user = User.getUser(request.getParameter("sig"));
			if (user.isPremium()) {  // do not allow the user to use this page
				Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
				if (a != null) response.sendRedirect("/" + a.assignmentType + "?sig=" + user.getTokenSignature());
				else out.println("Your subscription is active.");
				return;
			}
			Deployment deployment = ofy().load().type(Deployment.class).id(request.getParameter("d")).now();
			if (user==null || deployment==null) throw new Exception("You must be logged in through your class LMS to see this page.");
			out.println(checkoutPage(user,deployment));	
		} catch (Exception e) {
			out.println(Subject.header("Logout") + Logout.now(request,e) + Subject.footer);
		}
	}

	public void doPost(HttpServletRequest request,HttpServletResponse response)
	throws ServletException, IOException {
		PrintWriter out = response.getWriter();
		response.setContentType("application/json");
		JsonObject res = new JsonObject();
		User user = null;
		
		try {
			user = User.getUser(request.getParameter("sig"));
			if (user==null) throw new Exception("Launch the assignment from your LMS to access this page.");
			
			if (user.isPremium()) {  // do not allow the user to use this page
				Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
				if (a != null) response.sendRedirect("/" + a.assignmentType + "?sig=" + user.getTokenSignature());
				return;
			}
			
			String userRequest = request.getParameter("UserRequest");
			
			switch (userRequest) {
			case "ExtendFreeTrial":
				PremiumUser pu = ofy().load().type(PremiumUser.class).id(user.hashedId).now();
				Date now = new Date();
				if (pu == null) {
					Deployment deployment = ofy().load().type(Deployment.class).id(request.getParameter("d")).safe();
					pu = new PremiumUser(user.getHashedId(), deployment.getOrganization(), ONE_DAY); // constructor automatically saves new entity
				} else if (pu.start==null || new Date(pu.start.getTime()+ONE_WEEK).after(now)) {
					pu.start = (pu.start==null) ? now : pu.start;  // if start is null, set it to now; otherwise keep the original start date
					pu.exp = new Date(now.getTime()+ONE_DAY);
					ofy().save().entity(pu).now();
				} else throw new Exception("Your free trial period cannot be extended at this time.");
				res.addProperty("exp", pu.exp.toString());
				out.println(res.toString());
				break;
			case "RedeemVoucher":
				Date exp = redeemVoucher(user,request);
				res.addProperty("exp", exp.toString());
				out.println(res.toString());
				break;
			case "CreateOrder":
				String order_id = createOrder(user,request);
				res.addProperty("id", order_id);
				out.println(res.toString());
				break;
			case "CompleteOrder":
				out.println(completeOrder(user,request).toString());
				break;
			case "Logout":
				Logout.now(user);
				res.addProperty("success", true);
				out.println(res.toString());
				break;
			default:
				response.sendError(400);  // Bad request
			}
		} catch (Exception e) {
			res.addProperty("error", e.getMessage());
			out.println(res.toString());
			Logout.now(user);
		}
	}

	static String checkoutPage(User user, Deployment d) {
		StringBuffer buf = new StringBuffer();
		
		Date now = new Date();
		Assignment a = ofy().load().type(Assignment.class).id(user.getAssignmentId()).now();
		
		buf.append(Subject.header("ChemVantage Subscription", "checkout") + Subject.banner);
		
		// Store the PayPal client_id and user's sig value and platform_deployment_id in DOM elements so they can be accessed by javascript
		buf.append("<input type=hidden id=client_id value='" + Subject.getPayPalClientId() + "' />"); 		
		buf.append("<input type=hidden id=sig value=" + user.getTokenSignature() + " />");
		buf.append("<input type=hidden id=platform_deployment_id value=" + d.getPlatformDeploymentId() + " />");
		buf.append("<input type=hidden id=price value=" + price + " />");

		PremiumUser u = ofy().load().type(PremiumUser.class).id(user.getHashedId()).now();
		if (u==null) u = new PremiumUser(user.hashedId, d.organization, 0L);  // create a free trial PremiumUser if none exists
		else if (u.start == null) { // this condition is added to handle the case of existing PremiumUser entities created before the 'start' field was added; these users should be treated as new users who are just starting their free trial
			u.start = new Date();
			u.order_id = "FREE_TRIAL";
			ofy().save().entity(u).now();
		}
		Date freeTrialExpires = new Date(u.start.getTime()+ONE_WEEK);
		boolean withinFreeTrialPeriod = u.order_id!=null && u.order_id.equals("FREE_TRIAL") && now.before(freeTrialExpires);

		String title = null; 
		if (withinFreeTrialPeriod) title = "Individual ChemVantage Subscription";
		else title = "Your subscription expired on<br/>" + u.exp.toString() + ".";
		
		buf.append("<h1>" + title + "</h1>\n"
				+ "A subscription is required to access ChemVantage assignments created by your instructor through this learning management system. "
				+ "First, please indicate your agreement with the two statements below by checking the boxes.<br/><br/>"
				+ "<label><input type=checkbox id=terms onChange=showSelectPaymentMethod();> I understand and agree to the <a href=/terms_and_conditions.html target=_blank>ChemVantage Terms and Conditions of Use</a>.</label> <br/>"
				+ "<label><input type=checkbox id=norefunds onChange=showSelectPaymentMethod();> I understand that all ChemVantage subscription fees are non-refundable.</label> <br/><br/>");
		
		buf.append("<div id=select_payment_method style='display:none'>\n");

		// Place three buttons for payment options here
		buf.append("<p><b>Please select one of the options below:</b></p>"
				+ "<p><button class='btn btn-primary' onclick='showVoucherRedemption();'>&nbsp;Redeem a subscription voucher&nbsp;</button>&nbsp;&nbsp;"
				+ "<button class='btn btn-primary' onclick='showSubscriptionPurchase();'>&nbsp;Purchase a subscription&nbsp;</button></p>");
		if (withinFreeTrialPeriod && u.exp.equals(u.start)) { // only show this option for new users is still within the initial free trial period
				buf.append("<div id=postpone_payment><button class='btn btn-secondary' onclick=extendFreeTrial('" + user.getTokenSignature() + "');>&nbsp;Start now, pay later.&nbsp;</button></div>");
		}
		// Create div elements for voucher redemption and subscription purchase (initially hidden)
		buf.append("<div id=voucher_redemption style='display: none'>"
				+ "Please enter the code code here: "
				+ "<input id=voucher_code type=text size=10 />&nbsp;"
				+ "<button class='btn btn-primary' onclick=redeemVoucher('" + user.getTokenSignature() + "','" + d.getPlatformDeploymentId() + "');>Redeem</button><br/>"
				+ "</div>");  // end of voucher redemption div
		
		buf.append("<div id=subscription_purchase style='display: none'>"
				+ "Please select the desired number of months you wish to purchase:<br/>"
				+ "<div style='align: center'>"
				+ "<select id=nmonths>"
				+ "<option value=1>1 month - $" + 1*price + " USD</option>"
				+ "<option value=2>2 months - $" + 2*price + " USD</option>"
				+ "<option value=5 selected>5 months - $" + 4*price + " USD</option>"
				+ "<option value=12>12 months - $" + 8*price + " USD</option>"
				+ "</select>&nbsp;"
				+ "<button class='btn btn-primary' onclick=startCheckout();>Checkout</button>"
				+ "</div>");
		buf.append("</div>");  // end of 'subscription_purchase' div
		buf.append("</div>");  // end of 'select_payment_method' div
		
		// Create a div for displaying the PayPal payment buttons (initially hidden)
		buf.append("<div id=payment_div style='display: none'>"
				+ "Please select your method of payment:<br/><br/>"
				+ "<div id='paypal-button-container'></div>");
		buf.append("</div>");  // end of payment div
		
		buf.append("<div id=proceed style='display: none'><br/><br/>"
				+ "<a class='btn btn-primary' href='/" + a.assignmentType + "?sig=" + user.getTokenSignature() + "'>Proceed to your assignment</a><br/><br/>"
				+ "</div>");
		
		buf.append(Subject.footer);
		
		return buf.toString();
	}
	
	Date redeemVoucher(User user, HttpServletRequest request) throws Exception {
		PremiumUser pu = null;
		
		String voucherCode = request.getParameter("voucher_code");
		if (voucherCode==null || voucherCode.isEmpty()) throw new Exception("The code was missing or invalid.");
		voucherCode = voucherCode.toUpperCase();
		Voucher v = ofy().load().type(Voucher.class).id(voucherCode).now();
		if (v==null) throw new Exception("The code was missing  or invalid.");
		if (!v.activate()) { // check for duplicate submission by same user
			pu = ofy().load().type(PremiumUser.class).id(user.hashedId).safe();
			if (pu.order_id.equals(v.code)) return pu.exp;
			else throw new Exception("This voucher code has expired or was redeemed previously.");
		}

		// code is valid, so create a new PremiumUser
		Deployment deployment = ofy().load().type(Deployment.class).id(request.getParameter("d")).safe();
		if (pu == null) pu = new PremiumUser(user.getHashedId(), v.months, v.paid, deployment.getOrganization(),v.code); // constructor automatically saves new entity
		return pu.exp;
	}
	
	String createOrder(User user, HttpServletRequest request) throws Exception {	
		int nMonths = Integer.parseInt(request.getParameter("nmonths"));
		int value = price * (nMonths - nMonths/3);
		String platform_deployment_id = request.getParameter("d");
		
		// Build the order request using PayPal SDK
		OrderRequest orderRequest = new OrderRequest();
		orderRequest.checkoutPaymentIntent("CAPTURE");
		
		// Create purchase unit with amount
		List<PurchaseUnitRequest> purchaseUnits = new ArrayList<>();
		PurchaseUnitRequest purchaseUnit = new PurchaseUnitRequest()
			.description(nMonths + "-month ChemVantage subscription")
			.amountWithBreakdown(new AmountWithBreakdown()
				.currencyCode("USD")
				.value(value + ".00"));
		purchaseUnits.add(purchaseUnit);
		orderRequest.purchaseUnits(purchaseUnits);
		
		// Configure payment source (no shipping for digital goods)
		ApplicationContext applicationContext = new ApplicationContext()
			.shippingPreference("NO_SHIPPING");
		orderRequest.applicationContext(applicationContext);
		
		// Create order via PayPal SDK
		OrdersCreateRequest ordersCreateRequest = new OrdersCreateRequest();
		ordersCreateRequest.requestBody(orderRequest);
		
		HttpResponse<Order> response = getPayPalClient().execute(ordersCreateRequest);
		Order order = response.result();
		String order_id = order.id();
		
		// Save order to datastore
		ofy().save().entity(new PayPalOrder(order_id, new Date(), nMonths, value, user, platform_deployment_id));
		
		return order_id;
	}
	
	JsonObject completeOrder(User user, HttpServletRequest request) throws Exception {
		String order_id = request.getParameter("order_id");
		PayPalOrder order = ofy().load().type(PayPalOrder.class).id(order_id).safe();
		Deployment deployment = ofy().load().type(Deployment.class).id(order.platform_deployment_id).now();
		
		// Capture the order using PayPal SDK
		OrdersCaptureRequest ordersCaptureRequest = new OrdersCaptureRequest(order_id);
		HttpResponse<Order> response = getPayPalClient().execute(ordersCaptureRequest);
		Order capturedOrder = response.result();
		
		// Build response JSON
		JsonObject resp = new JsonObject();
		resp.addProperty("id", capturedOrder.id());
		resp.addProperty("status", capturedOrder.status());
		
		// Update order status
		order.status = capturedOrder.status();
		
		if ("COMPLETED".equals(capturedOrder.status())) {
			// Create new PremiumUser
			PremiumUser pu = new PremiumUser(user.getHashedId(), order.nMonths, order.value, 
				deployment.getOrganization(), order.id);
			resp.addProperty("expires", pu.exp.toString());
			
			// Add payment details to response
			if (capturedOrder.purchaseUnits() != null && !capturedOrder.purchaseUnits().isEmpty()) {
				PurchaseUnit unit = capturedOrder.purchaseUnits().get(0);
				if (unit.payments() != null && unit.payments().captures() != null && 
					!unit.payments().captures().isEmpty()) {
					Capture capture = unit.payments().captures().get(0);
					JsonObject paymentDetails = new JsonObject();
					paymentDetails.addProperty("amount", capture.amount().value());
					paymentDetails.addProperty("currency", capture.amount().currencyCode());
					resp.add("payment", paymentDetails);
				}
			}
		}
		
		ofy().save().entity(order);
		
		return resp;
	}
	
}

@Entity
class PayPalOrder {
	@Id 	String id;    // this is a PayPal-generated value for the path of API calls
	@Index 	Date created;
			int nMonths;
			int value;
			String hashedId;
			String platform_deployment_id;
			String status = "CREATED";
			
	PayPalOrder() {}
	PayPalOrder(String id, Date created, int nMonths, int value, User user, String platform_deployment_id) {
		this.id = id;
		this.created = created;
		this.nMonths = nMonths;
		this.value = value;
		this.hashedId = user.hashedId;
		this.platform_deployment_id = platform_deployment_id;
	}
}