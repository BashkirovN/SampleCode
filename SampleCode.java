/*
  Hi! You are looking at a sample of my working code. It's not super clean or well documented
  but it works and that's what I care the most about given my super limited time constraints.
  
  Some background:
    The program is a part of SMS sending feature of the CRM that I developed for a small moving company.
    It uses Twilio API for sending and receiving SMSs and Google Sheets API that acts as a database.
*/


package SMS;

import KZ2_Alyssa.Main;
import KZ2_Alyssa.SheetsAPI;
import SpringService.InboundTextMessage;

import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import static KZ2_Alyssa.SheetsAPI.updateConfirmationStatus;

public class SMSHandler {
    private static boolean allConfirmed = false;

    public static final String MANAGER_PHONE = "+14152282226";  
    
    
    
    /********************** Some code omitted *********************/



    //<JobId, List<Participant>> (Phones stored: +1xxxXXXxxxx)
    static ConcurrentHashMap<String, List<Participant>> awaitConfirmation = new ConcurrentHashMap<>();

    // Requests job confirmation from all participants via SMS.
    // Participants who already confirmed the job before won't get another SMS.
    public void requestJobConfirmations() {
        System.out.println(LocalDateTime.now() + ":: Requesting confirmations for tomorrow...");
        //System.out.println("DEBUG: \n\tEMPLOYEE_DB:\n\t\tKeys: "+EMPLOYEE_DB.keySet()+"\n\tValues: \n\t\t"+EMPLOYEE_DB.values());

        awaitConfirmation.clear();  // Just in case something was left from the previous day
        allConfirmed = false;

        int requestsSent = 0;
        List<SMSJob> jobList = SheetsAPI.getTomorrowsJobs();
        if (jobList != null) {
            for (SMSJob job : jobList) {
                switch (job.getJobStatus()) {
                    case SCHEDULED -> {
                        List<Participant> participants = getParticipants(job);
                        if (job.getConfirmations().equals("N/A")) { //Send requests

                            for (Participant participant : participants) {
                                if (participant.isCustomer()) {
                                    SMSSender.sendSMS(participant.getPhone(), getCustomerSMS(job));
                                } else {
                                    SMSSender.sendSMS(participant.getPhone(), getMoverSMS(job));
                                }
                                requestsSent += 1;
                            }

                            updateConfirmationStatus(job.getId(), "Requested", Integer.MAX_VALUE);
                        } else if (job.getConfirmations().equals("OK")) {                   //All participants confirmed the job
                            continue;   //Jumps to the next iteration
                        } else if (!job.getConfirmations().equals("Requested")) {           //Confirmation process was already initiated
                            int namesRemoved = 0;
                            for (String name : job.getConfirmations().split("\n")) { //Get responders' names and take them off the list
                                for (Participant participant : participants) {              //Keep only those who haven't confirmed yet
                                    if (participant.getName().equals(name)) {
                                        participants.remove(participant);
                                        namesRemoved++;
                                        break; // the inner loop
                                    }
                                }
                            }
                            if (namesRemoved == 0) { //Perhaps the input was invalid. Proceed but send a warning.
                                System.out.println(LocalDateTime.now() + ":: Couldn't request job confirmations. Unexpected Confirmations cell value.");
                                Main.writeLog(LocalDateTime.now() + ":: Couldn't request job confirmations. Unexpected Confirmations cell value.", true);
                                SMSSender.sendSMS(MANAGER_PHONE, "Couldn't request job confirmations. Unexpected Confirmations cell value.");
                            }
                        }
                        // else : Requests were sent but no one responded yet, add all names to the wait list.
                        // TODO: Ideally, I should remove it later. This would require only one confirmation from movers that have 2 jobs
                        participants.removeIf(this::isInParticipantList);

                        awaitConfirmation.put(job.getId(), participants); // Add to the map of jobs awaiting confirmation

                    }
                    case CONFIRMED, CANCELED -> {} // Just skip
                    case NEW, DECIDING -> // Something is not right. Was the job not confirmed timely? Send manager a text.
                            SMSSender.sendSMS(MANAGER_PHONE, "Warning: Job id-" + (job.getId()) + " is tomorrow but has status " + (job.getJobStatus()));
                    default -> {
                        System.out.println(LocalDateTime.now() + ":: Couldn't request job confirmations. Unexpected job status.");
                        Main.writeLog(LocalDateTime.now() + ":: Couldn't request job confirmations. Unexpected job status.", true);
                        SMSSender.sendSMS(MANAGER_PHONE, "Couldn't request job confirmations. Unexpected job status.");
                    }
                }
            }
            if (awaitConfirmation.isEmpty()) allConfirmed = true;
            System.out.println(LocalDateTime.now() + ":: Requesting confirmations for tomorrow...done! (" + requestsSent + " requests sent)");
        }
    }

    // Send manager a list of participants who didn't confirm the job timely
    public void reportUnconfirmedParticipants() {
        System.out.println(LocalDateTime.now() + ":: Checking if everyone has confirmed tomorrow jobs...");

        int confirmationsMissing = 0;
        if (!allConfirmed) {
            String report = "Some participants did not confirm tomorrow job:";
            for (Entry<String, List<Participant>> entry : awaitConfirmation.entrySet()) {
                report += "\n" + entry.getKey() + ": " + entry.getValue().toString().replace("[", "").replace("]", "");
                confirmationsMissing += entry.getValue().size();
            }
            SMSSender.sendSMS(MANAGER_PHONE, report);
        }
        System.out.println(LocalDateTime.now() + ":: Checking if everyone has confirmed tomorrow jobs...done! (" + confirmationsMissing + " confirmations missing)");
    }

    // Get an SMS text with information for a MOVER
    private String getMoverSMS(SMSJob job) {
        return "Job:\t\t" + job.getNumMovers() + " movers / " + (job.getTruck() == 0 ? "No" : job.getTruck()) + (job.getTruck() > 1 ? " trucks" : " truck") + " (" + job.getSize() + ")\n"
                + "Date:\t" + job.getDate() + "\n"
                + "Time:\t" + job.getTime() + "\n"
                + "Name:\t" + job.getCustomer() + "\n"
                + "Phone:\t" + job.getPhone() + "\n"
                + "Pickup:\n" + job.getOrigin() + "\n"
                + "Drop-off:\n" + job.getDestination() + "\n"
                + "Team:\t\n\t" + (job.getDriver().isEmpty() ? ("Foreman/Driver - " + job.getForeman()) : ("Foreman - " + job.getForeman() + "\n\tDriver - " + job.getDriver())) +
                (job.getHelpers().isEmpty() ? ("") : (",\n\tHelpers - " + job.getHelpers().toString().replace("[", "").replace("]", ""))) + "\n" +
                (job.getNote().isEmpty() ? ("") : ("Note:\t" + job.getNote() + "\n")) +
                "\n"
                + "Please confirm that you are ready for the job\n\t(Reply \"YES\" or \"NO\")";
    }

    // Get an SMS text with information for a CUSTOMER
    private String getCustomerSMS(SMSJob job) {
        return "Greetings from KZ2 Moving Company!\n\tYour move is scheduled for tomorrow, " + job.getDate() + " around " + job.getTime() + ".\n\t" +
                "Our team will contact you before coming and let you know the exact time of arrival.\n" +
                "\n\n\tPlease confirm that you will be waiting for us\n\t" +
                "(Reply \"YES\" or \"NO\")";
    }

    // If response is valid, remove participant from the wait list. Otherwise, send manager an SMS with the reply text.
    public String handleIncomingSMS(InboundTextMessage sms) {
        String response = sms.getBody().strip();
        Participant sender = getSender(sms.getFrom());

        //DEBUG
        boolean responseIsValid = response.equalsIgnoreCase("yes")
                || response.equalsIgnoreCase("confirm")
                || response.equalsIgnoreCase("ok")
                || response.equalsIgnoreCase("ок"); //In Russian for Norman...
        System.out.println("Incoming text::\n\tSender number(Twilio): " + sms.getFrom()
                + "\n\tSender(from getSender): " + sender + "\n\ttext: |"
                + response + "|\n\tIs Valid: " + responseIsValid);
        System.out.println("State snapshot:\n\tAwait Confirmation:\n\t\tKeys: "
                + awaitConfirmation.keySet() + "\n\tValues: \n\t\t" + awaitConfirmation.values());

        if (sender != null && responseIsValid) {
            //updateConfirmationStatus

            //Note: if a mover has 2 jobs on the same day, they will have to confirm twice.
            updateConfirmationStatus(sender.getJobId(), sender.getName(), (awaitConfirmation.get(sender.getJobId()).size() - 1));

            //Take the participant off the waiting list
            awaitConfirmation.get(sender.getJobId()).remove(sender);
            if (awaitConfirmation.get(sender.getJobId()).isEmpty()) {
                awaitConfirmation.remove(sender.getJobId());
            }
            if (awaitConfirmation.isEmpty()) { //Confirmation process is done for tomorrow
                allConfirmed = true;
                SMSSender.sendSMS(MANAGER_PHONE, "All tomorrow jobs were confirmed!");
            }

            return "Thank You for the confirmation\n(Automated message)";
        } else {
            //Send message to the manager
            String senderName;
            String senderNumber = sms.getFrom().substring(2, 5) + "-" + sms.getFrom().substring(5, 8) + "-" + sms.getFrom().substring(8);

            /*
            if (sms.getFrom().equals(OSCAR_PHONE)) {
                senderName = "Oscar";
            }   else if (sms.getFrom().equals(NORMAN_PHONE)) {
                senderName = "Norman";
            }  */

            if (EMPLOYEE_DB.containsValue(sms.getFrom())) {
                senderName = getKeyByValue(sms.getFrom());
            } else {
                senderName = SheetsAPI.getCustomerByPhoneNumber(senderNumber);
                senderName = senderName != null ? senderName : "Unexpected sender"; // This is neither a customer nor one of our employees
            }
            SMSSender.sendSMS(MANAGER_PHONE, "Incoming message from:\n\t" + senderName + " (" + senderNumber + ")\nThe message says:\n\t" + sms.getBody());
            return "";
        }
    }


    // Helper that searches map key by value
    private <K, V> K getKeyByValue(V value) {
        for (Entry<K, V> entry : ((Map<K, V>) SMSHandler.EMPLOYEE_DB).entrySet()) {
            if (entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // Look up the participant by phone number
    private Participant getSender(String phone) {
        for (Entry<String, List<Participant>> entry : awaitConfirmation.entrySet()) {
            for (Participant participant : entry.getValue()) {
                if (participant.getPhone().equals(phone)) {
                    return participant;
                }
            }
        }
        System.out.println(LocalDateTime.now() + "::" + phone + " not found in " + awaitConfirmation.values());
        System.out.println(LocalDateTime.now() + ":: Couldn't find the sender in awaitConfirmation list");
        Main.writeLog(LocalDateTime.now() + ":: Couldn't find the sender in awaitConfirmation list", true);
        return null;
    }

    // Takes an SMSJob object and returns a list of the customer and assigned movers.
    private List<Participant> getParticipants(SMSJob job) {
        List<Participant> participants = new ArrayList<>();
        //TODO: add decent database look-up for movers
        participants.add(new Participant(job.getId(), job.getCustomer(), ("+1" + job.getPhone()).replace("-", ""), true));

        participants.add(new Participant(job.getId(), job.getForeman(), EMPLOYEE_DB.get(job.getForeman()), false));
        participants.add(new Participant(job.getId(), job.getDriver(), EMPLOYEE_DB.get(job.getDriver()), false));
        for (String helperName : job.getHelpers()) {
            if (EMPLOYEE_DB.containsKey(helperName)) {
                participants.add(new Participant(job.getId(), helperName, EMPLOYEE_DB.get(helperName), false));
            } else {
                System.out.println("Warning: Couldn't find employee " + helperName + " in the database");
                Main.writeLog(LocalDateTime.now() + ":: Warning: Couldn't find employee " + helperName + " in the database", true);
            }
        }
        return participants;
    }

    // Returns true if participant is already in the waiting list (for any jpb)
    private boolean isInParticipantList(Participant participant) {
        for (String key : awaitConfirmation.keySet()) {
            if (awaitConfirmation.get(key).contains(participant)) return true;
        }
        return false;
    }

    // Represents an entity associated with an SMSJob. Customer or mover.
    private class Participant {
        private final String jobId;
        private final String name;
        private final String phone;
        private final boolean isCustomer;

        public Participant(String jobId, String name, String phone, boolean isCustomer) {
            this.jobId = jobId;
            this.name = name;
            this.phone = phone;
            this.isCustomer = isCustomer;
        }

        public String getJobId() {
            return jobId;
        }

        public String getName() {
            return name;
        }

        public String getPhone() {
            return phone;
        }

        public boolean isCustomer() {
            return isCustomer;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Participant p) {
                return p.name.equals(name) && p.phone.equals(phone);
            }
            return false;
        }

        @Override
        public String toString() {
            return "Participant:" + "\n\tName: " + name + "\n\tphone: " + phone + "\n\tjobId: " + jobId + "\n\tisCustomer: " + isCustomer;
        }
    }
}
