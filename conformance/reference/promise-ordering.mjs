const trace = [];

trace.push("sync");
queueMicrotask(() => trace.push("microtask"));
Promise.resolve().then(() => {
  trace.push("promise");
  queueMicrotask(() => trace.push("nested"));
});
trace.push("end");

setTimeout(() => {
  process.stdout.write(`${trace.join(",")}\n`);
}, 0);
