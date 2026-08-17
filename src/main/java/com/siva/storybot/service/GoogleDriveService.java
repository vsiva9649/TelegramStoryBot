//package com.siva.storybot.service;
//
//import com.google.api.services.drive.Drive;
//import com.google.api.services.drive.model.File;
//import com.google.api.services.drive.model.FileList;
//import lombok.RequiredArgsConstructor;
//import org.springframework.stereotype.Service;
//
//@Service
//@RequiredArgsConstructor
//public class GoogleDriveService {
//
//    private final Drive drive;
//
//    public void listAllFolders() throws Exception {
//
//        String query = "mimeType='application/vnd.google-apps.folder' " + "and 'me' in owners " + "and trashed=false";
//
//        FileList result = drive.files().list().setQ(query).setFields("files(id,name)").execute();
//
//        System.out.println("===== MY DRIVE FOLDERS =====");
//
//        for (File file : result.getFiles()) {
//
//            System.out.println("Folder Name : " + file.getName());
//
//            System.out.println("Folder ID : " + file.getId());
//
//            System.out.println("----------------");
//        }
//    }
//}