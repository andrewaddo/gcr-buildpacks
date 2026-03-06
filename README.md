# Cloud Run Source Deploy with Buildpacks, CMEK, and Auto-Updates

This project demonstrates how to natively build and deploy a Cloud Run service from source code using Google Cloud Buildpacks, integrating specific security and lifecycle features directly via `gcloud`.

## Requirements
1. **Automatic Updates on Base Image**: The deployed service must be configured to automatically apply security patches to the underlying OS/runtime layer without requiring a full code rebuild.
2. **Customer-Managed Encryption Keys (CMEK)**: The entire deployment process (including the build process, source code upload, build artifacts, and deployed container) must be encrypted using CMEK.
3. **CA Certificate Support**: Custom CA certificates must be made available to the application securely at runtime. Since Google Cloud Buildpacks do not natively inject CA certificates at build time (unlike Paketo Buildpacks), the most secure, Cloud-native approach is to mount the certificate as a Secret Manager volume directly into the container.

## Design Logics

1. **Secret Manager Volume Mounts for CA Certificates:**
   Rather than embedding credentials in the source code or using third-party buildpacks, we store the Java TrustStore (`.jks`) in Google Secret Manager. At deployment time, Cloud Run securely mounts this secret as a volume path (e.g., `/secrets/truststore.jks`). We then configure the runtime environment via `JAVA_TOOL_OPTIONS` to use this secure volume for TLS verification.

2. **CMEK for Source Deployments (`--no-allow-unencrypted-build`):**
   Fully compliant CMEK source deployments involve three encrypted assets:
   - A CMEK-encrypted Cloud Storage Bucket for uploading the zipped source code.
   - A CMEK-encrypted Artifact Registry repository for storing the built image.
   - The `--key` flag passed to Cloud Run to encrypt the deployed revision.
   - To force the build pipeline to respect CMEK, the `--no-allow-unencrypted-build` flag is required.

3. **Automatic Base Image Updates:**
   The `--automatic-updates` flag is used to subscribe the service to automatic updates. Due to an internal logic interaction in the `gcloud run deploy` command (where setting an explicit `--image` repository URI alongside `--automatic-updates` and CMEK can trigger validation errors because the inferred base image resets the image argument to `None`), we must explicitly supply the `--base-image` parameter (e.g., `.../serverless-runtimes/google-22/runtimes/java21`). This satisfies the Cloud Run validations while enabling automatic updates.

## Implementation Logics

The implementation requires setting up the appropriate infrastructure (KMS keys, Storage Buckets, Artifact Registry, Secrets), assigning the necessary IAM roles to system service agents, and executing the correct `gcloud` deploy command.

### 1. Set Up Environment Variables
```bash
export PROJECT_ID=$(gcloud config get-value project)
export REGION="australia-southeast1"
export REPO_NAME="test-repository"
export KEY_RING="cloudrun-demo-ring"
export KEY_NAME="state-key-44a6"
export PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')
```

### 2. Create KMS Keyring and Key
```bash
gcloud kms keyrings create $KEY_RING --location $REGION
gcloud kms keys create $KEY_NAME --location $REGION --keyring $KEY_RING --purpose "encryption"
```

### 3. Grant IAM Roles for CMEK Operations
Allow Artifact Registry, Cloud Build, Cloud Storage, and Cloud Run service agents to encrypt/decrypt using the key:

```bash
# Artifact Registry Service Agent
gcloud kms keys add-iam-policy-binding $KEY_NAME \
    --location $REGION \
    --keyring $KEY_RING \
    --member "serviceAccount:service-$PROJECT_NUMBER@gcp-sa-artifactregistry.iam.gserviceaccount.com" \
    --role "roles/cloudkms.cryptoKeyEncrypterDecrypter"

# Cloud Build Service Agent
gcloud kms keys add-iam-policy-binding $KEY_NAME \
    --location $REGION \
    --keyring $KEY_RING \
    --member "serviceAccount:$PROJECT_NUMBER@cloudbuild.gserviceaccount.com" \
    --role "roles/cloudkms.cryptoKeyEncrypterDecrypter"

# Cloud Run Service Agent
gcloud kms keys add-iam-policy-binding $KEY_NAME \
    --location $REGION \
    --keyring $KEY_RING \
    --member "serviceAccount:service-$PROJECT_NUMBER@serverless-robot-prod.iam.gserviceaccount.com" \
    --role "roles/cloudkms.cryptoKeyEncrypterDecrypter"

# Cloud Storage Service Agent
GS_SERVICE_ACCOUNT=$(gcloud storage service-agent --project=$PROJECT_ID)
gcloud kms keys add-iam-policy-binding $KEY_NAME \
    --location $REGION \
    --keyring $KEY_RING \
    --member "serviceAccount:$GS_SERVICE_ACCOUNT" \
    --role "roles/cloudkms.cryptoKeyEncrypterDecrypter"
```

### 4. Create CMEK-Encrypted Infrastructure

**Artifact Registry:**
```bash
gcloud artifacts repositories create $REPO_NAME \
    --repository-format=docker --location=$REGION \
    --kms-key="projects/$PROJECT_ID/locations/$REGION/keyRings/$KEY_RING/cryptoKeys/$KEY_NAME"
```

**Cloud Storage Bucket (for Source Code Upload):**
```bash
export SOURCE_BUCKET="gs://$PROJECT_ID-cloudrun-src"
gcloud storage buckets create $SOURCE_BUCKET \
    --location=$REGION \
    --default-encryption-key="projects/$PROJECT_ID/locations/$REGION/keyRings/$KEY_RING/cryptoKeys/$KEY_NAME"
```

### 5. Create the CA TrustStore Secret
Convert your custom `.pem` certificate into a Java KeyStore and upload it to Secret Manager:

```bash
# Create the truststore
keytool -import -alias custom-ca -file path/to/custom-ca.pem -keystore truststore.jks -storepass changeit -noprompt

# Create the secret
gcloud secrets create custom-ca-truststore --replication-policy="automatic" --data-file="truststore.jks"

# Grant the Cloud Run service account access to the secret
gcloud secrets add-iam-policy-binding custom-ca-truststore \
    --member="serviceAccount:$PROJECT_NUMBER-compute@developer.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
```

### 6. Package and Deploy
Because a CMEK build process requires uploading code to a designated, pre-configured bucket, we must zip the source locally, copy it to the bucket, and pass that bucket reference alongside the destination image repository to `gcloud run deploy`.

*Note: We mount the secret to `/secrets/truststore.jks` and configure the JVM to use it via `JAVA_TOOL_OPTIONS`.*

```bash
# Package source (excluding git and the tarball itself)
tar -czvf source.tgz --exclude .git --exclude source.tgz .
gcloud storage cp source.tgz $SOURCE_BUCKET/

# Deploy with CMEK, Secret Manager Volume Mount, and Automatic Updates
gcloud run deploy helloworld \
  --source $SOURCE_BUCKET/source.tgz \
  --image "$REGION-docker.pkg.dev/$PROJECT_ID/$REPO_NAME/helloworld" \
  --region $REGION \
  --key "projects/$PROJECT_ID/locations/$REGION/keyRings/$KEY_RING/cryptoKeys/$KEY_NAME" \
  --update-secrets="/secrets/truststore.jks=custom-ca-truststore:latest" \
  --update-env-vars="JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/secrets/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit" \
  --automatic-updates \
  --base-image="$REGION-docker.pkg.dev/serverless-runtimes/google-22/runtimes/java21" \
  --no-allow-unencrypted-build \
  --allow-unauthenticated
```

## Verification Steps

1. **Verify CMEK Encryption on the Deployed Service**:
   Run the following command to describe the service. You should see the `encryptionKey` field matching your KMS key.
   ```bash
   gcloud run services describe helloworld --region $REGION --format="value(spec.template.metadata.annotations['run.googleapis.com/encryption-key'])"
   ```

2. **Verify Automatic Base Image Updates**:
   Check the service annotations to confirm that automatic updates are enabled and the base image URI is tracked.
   ```bash
   gcloud run services describe helloworld --region $REGION --format="value(metadata.annotations['run.googleapis.com/build-enable-automatic-updates'])"
   gcloud run services describe helloworld --region $REGION --format="value(spec.template.metadata.annotations['run.googleapis.com/base-images'])"
   ```

3. **Verify CA Certificate Secret Mount**:
   Confirm that the `JAVA_TOOL_OPTIONS` environment variable and volume mount are correctly configured on the service template.
   ```bash
   # Verify the secret volume mount
   gcloud run services describe helloworld --region $REGION --format="value(spec.template.spec.volumes[0].secret.secretName)"
   
   # Verify the Java environment variable
   gcloud run services describe helloworld --region $REGION --format="value(spec.template.spec.containers[0].env[0].value)"
   ```
   Additionally, check the application logs to see the startup confirmation of the loaded CA (if your code includes truststore logging):
   ```bash
   gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=helloworld AND textPayload:DEBUG" --limit=5 --format="value(textPayload)"
   ```