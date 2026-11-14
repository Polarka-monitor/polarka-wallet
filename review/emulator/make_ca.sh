#!/bin/zsh
# A throwaway certificate authority and a server certificate for 10.0.2.2 (the host as an emulator sees it).
# For tests on emulators only. The keys stay in this folder; they protect nothing of value.
set -e
dir="$HOME/north-build/emu/tls"
mkdir -p "$dir"; chmod 700 "$dir"; cd "$dir"
if [ -f ca.pem ] && [ -f server.pem ]; then echo "already there"; else
  openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days 60 \
    -subj "/O=Polarka emulator tests/CN=Polarka emulator test CA (not for real use)" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=10.0.2.2" 2>/dev/null
  printf 'subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' > server.ext
  openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.pem -days 60 -extfile server.ext 2>/dev/null
  chmod 600 ca.key server.key
fi
openssl x509 -in ca.pem -noout -subject -enddate
echo "store name: $(openssl x509 -inform PEM -subject_hash_old -in ca.pem -noout).0"
openssl verify -CAfile ca.pem server.pem
