import test from "node:test";
import assert from "node:assert/strict";
import {createHandler} from "../../supabase/functions/finpop-api/handler.mjs";

const uid="11111111-1111-4111-8111-111111111111";
const gid="33333333-3333-4333-8333-333333333333";
const key="88888888-8888-4888-8888-888888888888";
function fixture({authStatus=200,authBody={id:uid},rpcStatus=200,rpcBody={httpStatus:200,body:{data:{ok:true},meta:{requestId:"stable"}}},throws=false}={}){
 const calls=[];
 const handler=createHandler({url:"https://example.supabase.co",publicKey:"public-fixture",serviceKey:"server-fixture",
   fetcher:async(url,options)=>{
     calls.push({url,options});
     if(throws)throw new Error("연결 오류");
     if(url.endsWith("/auth/v1/user"))return Response.json(authBody,{status:authStatus});
     return Response.json(rpcBody,{status:rpcStatus});
   }});
 return {handler,calls};
}
function request(path="/v1/games",body={mode:"AI",characterId:"CHEESE"},headers={}){
 return new Request("https://local/functions/v1/finpop-api"+path,{
   method:"POST",headers:{Authorization:"Bearer user-fixture","Content-Type":"application/json","Idempotency-Key":key,...headers},
   body:JSON.stringify(body),
 });
}
test("인증 누락 시 네트워크 호출 없이 차단",async()=>{
 const f=fixture();assert.equal((await f.handler(request(undefined,undefined,{Authorization:""}))).status,401);assert.equal(f.calls.length,0);
});
test("Auth 서버가 거절한 토큰은 RPC에 전달하지 않음",async()=>{
 const f=fixture({authStatus:401});assert.equal((await f.handler(request())).status,401);assert.equal(f.calls.length,1);
});
test("검증된 사용자 ID와 요청 키만 RPC에 전달",async()=>{
 const f=fixture();const r=await f.handler(request());assert.equal(r.status,200);
 const rpc=JSON.parse(f.calls[1].options.body);
 assert.equal(rpc.p_user_id,uid);assert.equal(rpc.p_operation,"create");assert.equal(rpc.p_request_key,key);
 assert.equal(f.calls[0].options.headers.Authorization,"Bearer user-fixture");
 assert.equal(f.calls[1].options.headers.Authorization,"Bearer server-fixture");
 assert.ok(!JSON.stringify(await r.json()).includes("server-fixture"));
});
test("사용자 ID 위조 필드 차단",async()=>{
 const f=fixture();assert.equal((await f.handler(request(undefined,{mode:"AI",characterId:"CHEESE",userId:uid}))).status,400);
 assert.equal(f.calls.length,0);
});
test("이동 경로를 roll RPC로 매핑",async()=>{
 const f=fixture();await f.handler(request("/v1/games/"+gid+"/rolls",{expectedVersion:2}));
 const rpc=JSON.parse(f.calls[1].options.body);assert.equal(rpc.p_operation,"roll");assert.equal(rpc.p_game_id,gid);
});
test("잘못된 버전 차단",async()=>{
 const f=fixture();assert.equal((await f.handler(request("/v1/games/"+gid+"/initiative",{expectedVersion:"2"}))).status,400);
});
test("요청 키 누락 차단",async()=>{
 const f=fixture();assert.equal((await f.handler(request(undefined,undefined,{"Idempotency-Key":""}))).status,400);
});
test("클라이언트 주사위 값 주입 차단",async()=>{
 const f=fixture();assert.equal((await f.handler(request("/v1/games/"+gid+"/rolls",{expectedVersion:2,diceValue:6}))).status,400);
});
test("본문 크기 제한",async()=>{
 const f=fixture();assert.equal((await f.handler(request(undefined,{mode:"AI",characterId:"X".repeat(17000)}))).status,413);
});
test("조회는 요청 키와 본문 없이 호출",async()=>{
 const f=fixture();assert.equal((await f.handler(new Request("https://local/v1/games/"+gid,{headers:{Authorization:"Bearer user-fixture"}}))).status,200);
 const rpc=JSON.parse(f.calls[1].options.body);assert.equal(rpc.p_operation,"get");assert.equal(rpc.p_request_key,null);
});
test("RPC의 충돌 상태와 최초 응답 보존",async()=>{
 const body={error:{code:"STATE_CONFLICT"},meta:{requestId:"cached"}};
 const f=fixture({rpcBody:{httpStatus:409,body}});
 const r=await f.handler(request());assert.equal(r.status,409);assert.deepEqual(await r.json(),body);
});
test("내부 DB 오류를 클라이언트에 노출하지 않음",async()=>{
 const f=fixture({rpcStatus:500,rpcBody:{message:"secret table details"}});
 const r=await f.handler(request());assert.equal(r.status,503);assert.ok(!JSON.stringify(await r.json()).includes("secret table"));
});
test("통신 실패는 재시도 가능한 오류",async()=>{
 const f=fixture({throws:true});assert.equal((await f.handler(request())).status,503);
});
test("미지원 API는 호출하지 않음",async()=>{
 const f=fixture();assert.equal((await f.handler(request("/v1/unknown"))).status,404);assert.equal(f.calls.length,0);
});
test("Auth 장애와 잘못된 사용자 응답",async()=>{
 let f=fixture({authStatus:503});assert.equal((await f.handler(request())).status,503);
 f=fixture({authBody:{}});assert.equal((await f.handler(request())).status,401);
});
test("잘못된 RPC 응답은 502",async()=>{
 const f=fixture({rpcBody:{httpStatus:999}});assert.equal((await f.handler(request())).status,502);
});


test("배포 게이트웨이 경로와 직접 경로에서 게임 생성 처리",async()=>{
 for(const prefix of ["/finpop-api","/functions/v1/finpop-api",""]){
  const f=fixture();
  const original=request();
  const deployed=new Request("https://local"+prefix+"/v1/games",original);
  assert.equal((await f.handler(deployed)).status,200);
  assert.equal(JSON.parse(f.calls[1].options.body).p_operation,"create");
 }
});

test("함수 이름과 접두사만 일치하는 다른 경로는 거절",async()=>{
 const f=fixture();
 const r=new Request("https://local/finpop-api-other/v1/games",request());
 assert.equal((await f.handler(r)).status,404);
 assert.equal(f.calls.length,0);
});

test("AI 이동은 별도 경로로 서버 연산을 호출",async()=>{
 const f=fixture();
 const response=await f.handler(request("/v1/games/"+gid+"/ai/rolls",{expectedVersion:2}));
 assert.equal(response.status,200);
 const body=JSON.parse(f.calls[1].options.body);
 assert.equal(body.p_operation,"ai-roll");assert.equal(body.p_user_id,uid);
});
test("AI 이동 요청에서도 주사위·참가자 위조 차단",async()=>{
 const f=fixture();
 assert.equal((await f.handler(request("/v1/games/"+gid+"/ai/rolls",{expectedVersion:2,diceValue:6,playerId:"P1"}))).status,400);
 assert.equal(f.calls.length,0);
});

test("사용자 답변·시간 초과·AI 판정 경로를 서버 연산에 매핑",async()=>{
 for(const [path,operation] of [["quiz-answers","answer"],["quiz-timeouts","quiz-timeout"],["ai/quiz-answers","ai-answer"]]){
  const f=fixture();
  const body={expectedVersion:3,attemptId:key,...(operation==="answer"?{choiceId:2}:{})};
  const response=await f.handler(request("/v1/games/"+gid+"/"+path,body));
  assert.equal(response.status,200);
  assert.equal(JSON.parse(f.calls[1].options.body).p_operation,operation);
 }
});
test("판정 요청의 정답·시간·AI 선택지 위조 차단",async()=>{
 for(const [path,body] of [
  ["quiz-answers",{expectedVersion:3,attemptId:key,choiceId:5}],
  ["quiz-answers",{expectedVersion:3,attemptId:"invalid",choiceId:1}],
  ["quiz-answers",{expectedVersion:3,attemptId:key,choiceId:1,remainingMillis:20000}],
  ["ai/quiz-answers",{expectedVersion:3,attemptId:key,choiceId:1}],
  ["quiz-timeouts",{expectedVersion:3,attemptId:key,correctChoiceId:1}],
 ]){
  const f=fixture();
  assert.equal((await f.handler(request("/v1/games/"+gid+"/"+path,body))).status,400);
  assert.equal(f.calls.length,0);
 }
});

test("구매·종료·무인도 경로는 전용 서버 연산으로 매핑",async()=>{
 const cases=[["purchases","purchase",{tileId:1,action:"BUY"}],["ai/purchases","ai-purchase",{tileId:1}],
 ["turn-completions","complete-turn",{turnId:key}],["ai/turn-completions","ai-complete-turn",{turnId:key}],
 ["island-skips","island-skip",{turnId:key}],["ai/island-skips","ai-island-skip",{turnId:key}]];
 for(const [path,operation,extra] of cases){
  const f=fixture();
  assert.equal((await f.handler(request("/v1/games/"+gid+"/"+path,{expectedVersion:4,...extra}))).status,200);
  assert.equal(JSON.parse(f.calls[1].options.body).p_operation,operation);
 }
});
test("구매 금액·AI 선택·턴 ID 위조 차단",async()=>{
 for(const [path,extra] of [["purchases",{tileId:1,action:"BUY",price:1}],["purchases",{tileId:1,action:"OTHER"}],
 ["ai/purchases",{tileId:1,action:"BUY"}],["turn-completions",{turnId:"invalid"}],["purchases",{tileId:14,action:"BUY"}]]){
  const f=fixture();
  assert.equal((await f.handler(request("/v1/games/"+gid+"/"+path,{expectedVersion:4,...extra}))).status,400);
  assert.equal(f.calls.length,0);
 }
});

for (const operation of ['pause','resume','abandon']) {
 test(operation+' 세션 명령은 인증된 RPC에 버전·요청 키를 전달',async()=>{
  const f=fixture();const r=await f.handler(request('/v1/games/'+gid+'/'+operation,{expectedVersion:4}));
  assert.equal(r.status,200);const rpc=JSON.parse(f.calls[1].options.body);
  assert.equal(rpc.p_operation,operation);assert.equal(rpc.p_game_id,gid);assert.equal(rpc.p_body.expectedVersion,4);assert.equal(rpc.p_request_key,key);
 });
 test(operation+' 클라이언트 시간·보상 주입 차단',async()=>{
  const f=fixture();const r=await f.handler(request('/v1/games/'+gid+'/'+operation,{expectedVersion:4,remainingQuizMs:20000,rewardGranted:true}));
  assert.equal(r.status,400);assert.equal(f.calls.length,0);
 });
}
