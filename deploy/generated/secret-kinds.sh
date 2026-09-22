# GENERATED FROM ../services.yaml — DO NOT EDIT.
#
# Sourced by setup.sh. One line per secret: `<name> <kind> [<seedFrom>]`.
#
# The third field is optional and names a file IN THIS REPOSITORY that the
# secret is seeded from when it exists — which today is the public key this
# project signs its own plugin manifests with. A public key is not a secret,
# and it travels as one because that is the mechanism a deployment already
# has for getting per-plugin material into a container with a read-only root.

SECRET_KINDS='postgres-superuser-password random
db-password random
db-migration-password random
data-encryption-master-key random
jwt-signing-key ed25519
bootstrap-password random
credential-key random
url-signing-key random
valkey-password random
mq-password random
search-password random
mtls-core mtls-client
mtls-blobstore mtls-server
mtls-plugin-webhook mtls-server
mtls-plugin-oidc mtls-server
plugin-oidc-client-secret external
mtls-plugin-blobstore-nextcloud mtls-server
plugin-blobstore-nextcloud-password external
mtls-plugin-blobstore-s3 mtls-server
plugin-blobstore-s3-secret-key external
mtls-plugin-smtp mtls-server
cosign-plugin-webhook cosign-public keys/home-inv-plugins.pub.pem
cosign-plugin-smtp cosign-public keys/home-inv-plugins.pub.pem
cosign-plugin-blobstore-s3 cosign-public keys/home-inv-plugins.pub.pem
cosign-plugin-blobstore-nextcloud cosign-public keys/home-inv-plugins.pub.pem
cosign-plugin-oidc cosign-public keys/home-inv-plugins.pub.pem
plugin-smtp-password external
mtls-search mtls-server
opensearch-admin-password random'
