const address = 'wss://localhost:8099/echo';

const startMessage = 'start';
const stopMessage = 'stop';

const stateView = document.getElementById('state');
const sentView = document.getElementById('sent');
const receivedView = document.getElementById('received');
const counterView = document.getElementById('counter');
const pingButton = document.getElementById('ping');

let receivedCounter = 0;
let sentCounter = 0;
let isStarted = false;
let pingHandle;
pingButton.innerHTML = startMessage;

const websocket = new WebSocket(address);

websocket.onopen = () => {
   pingButton.disabled = false;
   stateView.style.textDecoration = 'underline';
   stateView.style.textDecorationColor = 'green';
   stateView.innerHTML = `state: connected ${address}`;
};

websocket.onmessage = msg => {
   receivedCounter++;
   receivedView.innerHTML = `received: ${msg.data}`;
   counterView.innerHTML = `counter: ${receivedCounter}`;
};

websocket.onerror = error => {
   pingButton.disabled = true;
   if (pingHandle) {
       clearInterval(pingHandle);
   }
   stateView.style.textDecoration = 'underline';
   stateView.style.textDecorationColor = 'red';
   stateView.innerHTML = `state: disconnected, websocket error ${address}`;
};

pingButton.onclick = () => {
   if (isStarted) {
       if (pingHandle) {
          clearInterval(pingHandle);
          pingHandle = null;
       }
       pingButton.innerHTML = startMessage;
   } else {
      pingHandle = setInterval(() => {
         const message = JSON.stringify({ content: new Date().toUTCString() });
         sentView.innerHTML = `sent: ${message}`;
         websocket.send(message);
      },
      1000);
      pingButton.innerHTML = stopMessage;
   }
   isStarted = !isStarted;
}