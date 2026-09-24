# Shared proxy settings for everything the backend answers.
proxy_pass http://backend:8080;
proxy_http_version 1.1;

proxy_set_header Host $host;
proxy_set_header X-Forwarded-Proto $forwarded_scheme;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Request-ID $infradesk_request_id;

# A controlled SSH operation may legitimately take minutes; an unbounded wait may not.
proxy_connect_timeout 5s;
proxy_send_timeout 300s;
proxy_read_timeout 300s;
