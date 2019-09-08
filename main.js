var fs = require("fs")
var express = require("express")
var hbs = require('express-handlebars')
var axios = require("axios")
var cheerio = require("cheerio")

var app = express()

// 중요하지 않은부분 ( 템플릿엔진 세팅 )
app.engine('hbs', hbs({
    extname: 'hbs',
    defaultLayout: false,
}))
app.set('view engine', 'hbs')

// mysql 모니터(커넥터) 를 js 로 키기
var mysql = require("mysql")
var connection = mysql.createConnection({
    host     : 'localhost',
    user     : 'root',
    password : 'seoul1234',
    database : 'testdb'
})

connection.connect()

/*
    1) 미들웨어    선택적미들웨어 vs 공용미들웨어
    2) database 의 본질은 txt 파일이다
    3) RDS 중에 하나인 mysql 사용하기
    4) database table column row
    5) mysql 커넥터(모니터) 를 node.js 코드로 키는법
    
    6) 템플릿엔진 원리
    7) handlebars 사용법
    8) {{ name }} 콧수염빵꾸
    9) each 를 이용한 반복
    10) if 를 이용한 조건

    11) ajax 로 코드로 http 요청을 만들어내고 코드로 http 응답을 받는법
    12) XMLHttpRequest 라는 기본을 이용
    13) 대신에 axios 라는 라이브러리를 이용

    14) 서버에서도 axios를(http) 요청할수있다 ( 다른서버로 )
    15) 받아온 html 데이터를 마치 제이쿼리처럼 다룰수있다 (cheerio 라는 라이브러리 이용) (자세히 배우지 않음)
    16) 보내주면 클라이언트에서 사용 ( 알아서 )
*/


// 쿠키파싱을 위한 모듈
var cookieParser = require("cookie-parser")

// 쿠키파싱 공통미들웨어 등록
app.use(cookieParser())
// /public/test.css /public/main.js 같이 접두사가 /public 인경우 public 폴더에서 찾아서 보내주기
app.use("/public", express.static("public"))

// 쿠키를 체크하는부분을 미들웨어로 분리
function loginMiddle(req, res, next){
    console.log(req.cookies.user)
    if(req.cookies.user){   
        //{ login : true, name : "kihyun" }
        //j%3A%7B%22login%22%3Atrue%2C%22name%22%3A%22kihyun%22%7D
        next()
    } else {
        res.redirect("/login")
    }
}

var db_name = "기현"
var db_age = 27

app.get("/user", function(req, res){
    fs.readFile("user.html", function(err, data){
        var html = data.toString()
        html = html.replace("바꿀이름", db_name).replace("바꿀나이", db_age)
        res.send(html)
    })
})

app.get("/user2", function(req, res){
    // 1) user.hbs 를 읽어옴  fs.readFile
    // 2) replace 빵꾸채움, for, if 로 html 만들고    .replace
    // 3) res.send() 로 만들어진 html 을 보내줌


    res.render("user", {
        name : db_name,
        age : db_age,
    })
})

app.get("/users", function(req, res){

    connection.query("select * from users", function(err, rows){

        var html = ""

        for(var i = 0; i < rows.length; i++){

            html = html + "<div>" + rows[i].user_id + "</div>"

        }

        res.send(html)
    })

})

// 템플릿 엔진에서 반복(for) 사용
app.get("/for", function(req, res){
    connection.query("select * from users", function(err, rows){
        res.render("for", {
            users : rows,
        })
    })
})


// 템플릿 엔진에서 조건(if) 사용
app.get("/if", function(req, res){
    res.render("if", {
        user_id : "kihyun"
    })
})

app.get("/join", function(req, res){
    fs.readFile("join.html", function(err, data){
        res.send(data.toString())
    })
})

// 회원가입시에 데이터 데이터베이스에 넣기
app.get("/joinprocess", function(req, res){

    var id = req.query.myid
    var pw = req.query.mypw

    connection.query("insert into users(user_id, user_pw) values(?, ?)", [id, pw], function(err, result){
        res.redirect("/users")
    })

})








// user.html 읽어서 보내주기
app.get("/user", function(req, res){
    fs.readFile("user.html", function(err, data){
        var str = data.toString()
        res.send(str)
    })
})

// 로그인미들웨어 사용하기
app.get("/", loginMiddle, function(req, res){
    fs.readFile("main.html", function(err, data){
        var str = data.toString()
        res.send(str)
    })
})

app.get("/login", function(req, res){
    fs.readFile("login.html", function(err, data){
        var str = data.toString()
        res.send(str)
    })
})

var real_id = "kihyun"
var real_pw = "123123"

app.get("/loginprocess", function(req, res){

    req.query.myid
    req.query.mypw

    if(real_id == req.query.myid && real_pw == req.query.mypw){
        // 아이디와 비밀번호가 일치해서
        // 로그인 성공시에
        // 쿠키 발급해주기

        res.cookie("user", { login : true, name : "kihyun" })
        res.redirect("/")

    } else {

        res.redirect("fail")

    }
})

// 성공페이지
app.get("/success", function(req, res){
    res.send("성공")
})

// 실패페이지
app.get("/fail", function(req, res){
    res.send("실패")
})

// ajax 를 위한 page 보내주는중
app.get("/serve", function(req, res){
    res.render("serve")
})

// ajax 로 http 요청이 들어오면
app.get("/ajax", function(req, res){
    
    // 서버에서도 http 요청을 네이버로 날리고
    axios.get("http://www.naver.com").then(function(response){

        var $ = cheerio.load(response.data)

        var data = []

        // 이부분은 아직 자세히 설명 안한부분 ( 파싱부분 )
        $(".ah_k").each(function(){

            data.push($(this).text())

        })

        // 그걸 파싱해서 보내는중
        res.send(data)

    })

})


app.listen(80, function(){
    console.log("포트를 열었습니다.")
})