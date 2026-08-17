//package com.siva.storybot.config;
//
//import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
//import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
//import com.google.api.client.http.javanet.NetHttpTransport;
//import com.google.api.client.json.gson.GsonFactory;
//import com.google.api.services.drive.Drive;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//@Configuration
//public class GoogleDriveConfig {
//
//    @Value("${google.client.id}")
//    private String clientId;
//
//    @Value("${google.client.secret}")
//    private String clientSecret;
//
//    @Value("${google.refresh.token}")
//    private String refreshToken;
//
//    @Bean
//    public Drive googleDrive() throws Exception {
//
//        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
//
//        GoogleCredential credential = new GoogleCredential.Builder().setTransport(httpTransport).setJsonFactory(GsonFactory.getDefaultInstance()).setClientSecrets(clientId, clientSecret).build().setRefreshToken(refreshToken);
//
//        credential.refreshToken();
//
//        return new Drive.Builder(httpTransport, GsonFactory.getDefaultInstance(), credential).setApplicationName("Story Bot").build();
//    }
//}