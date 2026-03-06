package com.example;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.security.KeyStore;
import java.util.Collections;

@SpringBootApplication
@RestController
public class Helloworld {
    public static void main(String[] args) {
        try {
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof javax.net.ssl.X509TrustManager) {
                    for (java.security.cert.X509Certificate cert : ((javax.net.ssl.X509TrustManager) tm).getAcceptedIssuers()) {
                        if (cert.getSubjectX500Principal().getName().contains("Demo-CA")) {
                             System.out.println("DEBUG: Found Custom CA: " + cert.getSubjectX500Principal().getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        SpringApplication.run(Helloworld.class, args);
    }

    @GetMapping("/")
    public String checkCert() throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null); // Use default
        // In a real app, you'd check for your specific alias here
        int certCount = Collections.list(ks.aliases()).size();
        return "Java 25 active. TrustStore contains " + certCount + " certificates.";
    }
}