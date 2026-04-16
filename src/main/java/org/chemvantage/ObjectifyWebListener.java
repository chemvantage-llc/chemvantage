package org.chemvantage;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import org.chemvantage.LTIRegistration.RegistrationCode;

import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.googlecode.objectify.ObjectifyFactory;
import com.googlecode.objectify.ObjectifyService;

@WebListener
public class ObjectifyWebListener implements ServletContextListener {

  @Override
  public void contextInitialized(ServletContextEvent event) {
	  // Use the next line to connect to a backup (or other non-default) datastore
	  //final Datastore datastore = DatastoreOptions.newBuilder().setDatabaseId("backup1").build().getService();
	  final Datastore datastore = DatastoreOptions.newBuilder().build().getService();
	  ObjectifyService.init(new ObjectifyFactory(datastore));
    
    // This is a good place to register your POJO entity classes.
    ObjectifyService.register(Assignment.class);
    ObjectifyService.register(Concept.class);
    ObjectifyService.register(Contact.class);
    ObjectifyService.register(Deployment.class);
    ObjectifyService.register(EmailMessage.class);
    ObjectifyService.register(Group.class);
    ObjectifyService.register(HWTransaction.class);
    ObjectifyService.register(Instructor.class);
    ObjectifyService.register(Nonce.class);
    ObjectifyService.register(PayPalOrder.class);
    ObjectifyService.register(PollTransaction.class);
    ObjectifyService.register(PlacementExamTransaction.class);
    ObjectifyService.register(PracticeExamTransaction.class);
    ObjectifyService.register(PremiumUser.class);
    ObjectifyService.register(ProposedQuestion.class);
    ObjectifyService.register(ProvisionalDeployment.class);
    ObjectifyService.register(Question.class);
    ObjectifyService.register(QuizTransaction.class);
    ObjectifyService.register(RegistrationCode.class);
    ObjectifyService.register(Referral.class);
    ObjectifyService.register(RSAKeyPair.class);
    ObjectifyService.register(Score.class);
    ObjectifyService.register(SageTransaction.class);
    ObjectifyService.register(STTransaction.class);
    ObjectifyService.register(Subject.class);
    ObjectifyService.register(Text.class);
    ObjectifyService.register(Topic.class);
    ObjectifyService.register(User.class);
    ObjectifyService.register(UserReport.class);
    ObjectifyService.register(Video.class);
    ObjectifyService.register(VideoTransaction.class);
    ObjectifyService.register(Voucher.class);
    
  }
	
  @Override
  public void contextDestroyed(ServletContextEvent event) {
  }
}