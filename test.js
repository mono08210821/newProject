var express = require("express")
var app = express()
var fs = require("fs")

app.get("/", function(req, res){
    fs.readFile("main.html", function(err, data){
        var html = data.toString()
        res.send(html)
    })
})

app.listen(80, function(){
    console.log("포트열림")
})