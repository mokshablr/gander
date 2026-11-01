document.getElementById("fname").textContent = vwName;
var hint = document.getElementById("hint");
// A slide show (.pps) and a template (.pot) are in the same old binary format as a .ppt
if (vwExt === "ppt" || vwExt === "pps" || vwExt === "pot") {
  hint.textContent =
    "This is a legacy Office format (." + vwExt + "). If you re-save it as .pptx, " +
    "Gander can display it.";
} else {
  // Nothing useful to add; leave the card to the action below
  hint.style.display = "none";
}

document.getElementById("asText").addEventListener("click", function () {
  location.replace(
    "text.html?name=" + encodeURIComponent(vwName) + "&ext=" + encodeURIComponent(vwExt)
  );
});
