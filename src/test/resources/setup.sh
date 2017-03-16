#!/bin/bash

# We pass the namespace on each command individually, because when this script is run inside a pod, all commands default to the pod namespace (ignoring commands like `oc project` etc)
echo "Installing IPaaS in ${KUBERNETES_NAMESPACE}"
oc replace -f https://raw.githubusercontent.com/redhat-ipaas/openshift-templates/master/serviceaccount-as-oauthclient-single-tenant.yml -n ${KUBERNETES_NAMESPACE} || oc create -f https://raw.githubusercontent.com/redhat-ipaas/openshift-templates/master/serviceaccount-as-oauthclient-single-tenant.yml -n ${KUBERNETES_NAMESPACE}
oc replace -f https://raw.githubusercontent.com/redhat-ipaas/openshift-templates/master/redhat-ipaas-dev-single-tenant.yml -n ${KUBERNETES_NAMESPACE}  || oc create -f https://raw.githubusercontent.com/redhat-ipaas/openshift-templates/master/redhat-ipaas-dev-single-tenant.yml -n ${KUBERNETES_NAMESPACE}
oc new-app redhat-ipaas-dev-single-tenant \
    -p ROUTE_HOSTNAME=ipaas-testing.b6ff.rh-idev.openshiftapps.com \
    -p OPENSHIFT_MASTER=$(oc whoami --show-server) \
    -p OPENSHIFT_OAUTH_CLIENT_ID=system:serviceaccount:$(oc project -q):ipaas-oauth-client \
    -p OPENSHIFT_OAUTH_CLIENT_SECRET=$(oc sa get-token ipaas-oauth-client -n ${KUBERNETES_NAMESPACE}) \
    -p OPENSHIFT_OAUTH_DEFAULT_SCOPES="user:info user:check-access role:edit:$(oc project -q):! role:system:build-strategy-source:$(oc project -q)" \
    -p GITHUB_OAUTH_CLIENT_ID="iocanel" \
    -p GITHUB_OAUTH_CLIENT_SECRET="changeme" \
    -n ${KUBERNETES_NAMESPACE}