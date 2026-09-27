# Shared proxy settings for everything the backend answers.
proxy_pass http://backend:8080;
proxy_http_version 1.1;

proxy_set_header Host $http_host;
proxy_set_header X-Forwarded-Proto $forwarded_scheme;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Request-ID $infradesk_request_id;

# Establishing the backend connection should always be fast. Response budgets are assigned by
# route in infradesk.conf so ordinary API traffic cannot occupy a proxy worker for 45 minutes.
proxy_connect_timeout 5s;
