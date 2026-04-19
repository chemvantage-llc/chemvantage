package org.chemvantage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/auth/token")
public class Token extends HttpServlet {
	@Serial
	private static final long serialVersionUID = 1L;
	
	// This servlet is the OpenID Connection starting point for platforms to reach ChemVantage
	// The servlet identifies the deployment corresponding to the request, and returns a Java Web Token 
	// containing information needed for the subsequent launch request or other service request.

	protected void doGet(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
		StringBuffer debug = new StringBuffer("");
		try {
			// store parameters required by third-party initiated login procedure:
			String platform_id = request.getParameter("iss");   // this should be the platform_id URL (aud)
			if (platform_id == null) throw new Exception("Missing required iss parameter.");
			// production server does not accept connections from ngrok.com domains
			if (platform_id.contains("ngrok") && Subject.getProjectId().equals("chem-vantage-hrd")) response.sendError(401);
			
			String login_hint = request.getParameter("login_hint");
			if (login_hint == null) throw new Exception("Missing required login_hint parameter.");
			
			String target_link_uri = request.getParameter("target_link_uri");
			if (target_link_uri == null) throw new Exception("Missing required target_link_uri parameter.");
			
			Deployment d = getDeployment(request);  // throws Exception if deployment not found
			
			String redirect_uri = target_link_uri;
			
			Date now = new Date();
			Date exp = new Date(now.getTime() + 300000L); // 5 minutes from now
			String nonce = Nonce.generateNonce();
			Algorithm algorithm = Algorithm.HMAC256(Subject.getHMAC256Secret());
			
			debug.append("JWT algorithm loaded OK.<br>");
			
			String iss = "https://" + request.getServerName();
			
			String token = JWT.create()
					.withIssuer(iss)
					.withSubject(login_hint)
					.withAudience(platform_id)
					.withExpiresAt(exp)
					.withIssuedAt(now)
					.withClaim("nonce", nonce)
					.withClaim("deployment_id",d.getDeploymentId())
					.withClaim("client_id", d.client_id)
					.withClaim("redirect_uri", redirect_uri)
					.sign(algorithm);
			
			debug.append("JWT constructed and signed OK<br>");
			String lti_message_hint = request.getParameter("lti_message_hint");
			
			String oidc_auth_url = d.oidc_auth_url
					+ "?response_type=id_token"
					+ "&response_mode=form_post"
					+ "&scope=openid"
					+ "&prompt=none"
					+ "&login_hint=" + login_hint
					+ "&redirect_uri=" + redirect_uri
					+ (lti_message_hint==null?"":"&lti_message_hint=" + lti_message_hint)
					+ "&client_id=" + d.client_id
					+ "&state=" + token
					+ "&nonce=" + nonce;
			
			debug.append("Sending token: " + oidc_auth_url + "<p>");
			
			response.setContentType("text/html");
			PrintWriter out = response.getWriter();
			StringBuffer buf = new StringBuffer();
			buf.append(Subject.header());
			//buf.append("<div style='min-height:100vh;display:flex;align-items:center;justify-content:center;'><img src='https://images.chemvantage.org/logo.png' alt='ChemVantage logo'></div>");
			buf.append("<script>"
				+ "window.location.replace('" + oidc_auth_url + "');"
				//+ "setTimeout(function(){window.location.replace('" + oidc_auth_url + "');},1000);"
				+ "</script>");
			buf.append(Subject.footer);
			//if (oidc_auth_url.contains("imc")) Utilities.sendEmail("ChemVantage", "admin@chemvantage.org", "IMC OIDC Auth URL", oidc_auth_url);
			out.println(buf.toString());
		} catch (Exception e) {
			Enumeration<String> parameterNames = request.getParameterNames();
			while (parameterNames.hasMoreElements()) {
				String name = parameterNames.nextElement();
				debug.append(name + ":" + request.getParameter(name) + ";");
			}
			response.sendError(401,"Failed Auth Token. " + (e.getMessage()==null?e.toString():e.getMessage()) + "\n" + debug.toString());
		}
	}
	
	private Deployment getDeployment(HttpServletRequest request) throws Exception {
		// This method attempts to identify a unique registered Deployment entity based on the required
		// platform_id value and the optional lti_deployment_id and client_id values. The latter should 
		// be used in case the platform supports multiple deployments with different client_id values for the tool.
		// However, this is not technically required by the specifications. Hmm.
		Deployment d = null;
		String platform_id = request.getParameter("iss");   // this should be the platform_id URL (aud)
		if (platform_id.endsWith("/")) platform_id = platform_id.substring(0,platform_id.length()-1);  // strip any trailing / from platform_id

		URL platform = new URI(platform_id).toURL();
		if (!platform.getProtocol().equals("https")) throw new Exception("The platform_id must be a secure URL.");

		// Take the optimistic route first; this should always work if the deployment_id has been provided, else return null;
		String deployment_id = request.getParameter("lti_deployment_id");  // moodle, brightspace, blackboard, canvas
		if (deployment_id == null) deployment_id = request.getParameter("login_hint");  // schoology

		String platform_deployment_id = platform_id + "/" + deployment_id;
		d = ofy().load().type(Deployment.class).id(platform_deployment_id).now();
		if (d != null) return d;
		
		/*
		 * End of normal getDeployment process.
		 * What follows is when a previously unregistered deployment is encountered
		 * The system attempts to associate it with a dynamic registration that may have 
		 * been missing a deployment_id. Otherwise it tries to create an anonymous 
		 * deployment and notifies the ChemVantage administrator.
		 */
		String client_id = request.getParameter("client_id");
		String contact_name = null;
		String email = null;
		String organization = null;
		String org_url = null;
		String oidc_auth_url = null;
		String oauth_access_token_url = null;
		String well_known_jwks_url = null;
		String lms = "other";
		
		try {  // try to get any missing contact data from a ProvisionalDeployment
			List<ProvisionalDeployment> pds = ofy().load().type(ProvisionalDeployment.class).filter("platformId",platform_id).filter("client_id", client_id).list();
			ProvisionalDeployment pd = pds.get(0); // if there are multiples, they should all be identical or at least valid
			contact_name = pd.contact_name;
			email = pd.email;
			organization = pd.organization;
			org_url = pd.org_url;
			ofy().delete().entities(pds); // delete all instances of matching provisional deployments
		} catch (Exception e) {}
		
		if ("https://canvas.instructure.com".equals(platform_id)) {  // auto register canvas account
			oidc_auth_url = "https://sso.canvaslms.com/api/lti/authorize_redirect";
			oauth_access_token_url = "https://sso.canvaslms.com/login/oauth2/token";
			well_known_jwks_url = "https://sso.canvaslms.com/api/lti/security/jwks";
			lms = "canvas";
		} else if ("https://schoology.schoology.com".equals(platform_id)) {  // auto register schoology account
			oidc_auth_url = "https://lti-service.svc.schoology.com/lti-service/authorize-redirect";
			well_known_jwks_url = "https://lti-service.svc.schoology.com/lti-service/.well-known/jwks";
			oauth_access_token_url = "https://lti-service.svc.schoology.com/lti-service/access-token";
			lms = "schoology";
		} else if ("https://blackboard.com".equals(platform_id)) {
			oidc_auth_url = "https://developer.blackboard.com/api/v1/gateway/oidcauth";
			well_known_jwks_url = "https://developer.blackboard.com/api/v1/management/applications/be1004de-6f8e-45b9-aae4-2c1370c24e1e/jwks.json";
			oauth_access_token_url = "https://developer.blackboard.com/api/v1/gateway/oauth2/jwttoken";
			lms = "blackboard";
		} else if (platform_id != null && (platform_id.contains("desire2learn.com") || platform_id.contains("brightspace.com"))) {
			client_id = request.getParameter("client_id");
			oidc_auth_url = platform_id + "/d2l/lti/authenticate";
			well_known_jwks_url = platform_id + "/d2l/.well-known/jwks";
			oauth_access_token_url = "https://auth.brightspace.com/core/connect/token";
			lms = "brightspace";
		} else {
			throw new Exception("Deployment Not Found. ChemVantage was unable to identify this deployment from your LMS. "
					+ "If you received a registration email within the past 7 days, please use the tokenized link in that message to "
					+ "submit (or resubmit) the deployment_id and other required parameters. Otherwise, you may "
					+ "repeat the registration process at https://www.chemvantage.org/lti/registration");
		}
			
		d = new Deployment(platform_id,deployment_id,client_id,oidc_auth_url,oauth_access_token_url,well_known_jwks_url,contact_name,email,organization,org_url,lms);
		d.status = "auto";
		d.nLicensesRemaining = 0;
		ofy().save().entity(d).now();

		String message = "<h3>Deployment Registration</h3>";
		message += "LMS: " + lms + "<br/>";
		try {
			String token = request.getParameter("lti_message_hint");
			String canvas_domain = JWT.decode(token).getClaims().get("canvas_domain").asString();
			message += "Canvas domain: " + canvas_domain + "<br/><br/>";
		} catch (Exception e) {}
		Map<String,String[]> params = request.getParameterMap();
		message += "Query parameters:<br/>";
		for (String name : params.keySet()) message += name + "=" + params.get(name)[0] + "<br/>";
		Utilities.sendEmail("ChemVantage","admin@chemvantage.org","Automatic Registration",message);
		return d;
	}

	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
		doGet(request, response);
	}
	
}
