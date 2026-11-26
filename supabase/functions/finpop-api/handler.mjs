const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 사용자 JWT는 Auth 서버에 검증하고, DB 변경은 서버 전용 RPC 한 번으로 처리합니다. */
export function createHandler({url, publicKey, serviceKey, fetcher=fetch, uuid=()=>crypto.randomUUID()}) {
  if(!url || !publicKey || !serviceKey) throw new Error("서버 Supabase 환경변수가 필요합니다.");
  const base=url.replace(/\/$/,"");
  return async request=>{
    const error=(status,code,message)=>Response.json({
      error:{code,message,details:{}},meta:{serverTime:new Date().toISOString(),requestId:uuid()},
    },{status,headers:{"Cache-Control":"no-store"}});
    try {
      // Supabase 경유 시 함수 이름만 남는 경로와 전체 URL 경로를 모두 처리합니다.
      const pathname=new URL(request.url).pathname.replace(/^\/(?:functions\/v1\/)?finpop-api(?=\/|$)/,"");
      let operation,gameId=null;
      if(request.method==="GET" && pathname==="/v1/game-config") operation="config";
      else if(request.method==="POST" && pathname==="/v1/games") operation="create";
      else{
        const match=pathname.match(/^\/v1\/games\/([^/]+)(?:\/(initiative|rolls|ai\/rolls|quiz-answers|quiz-timeouts|ai\/quiz-answers|purchases|ai\/purchases|turn-completions|ai\/turn-completions|island-skips|ai\/island-skips|pause|resume|abandon))?$/);
        if(!match) return error(404,"RESOURCE_NOT_FOUND","요청한 API를 찾을 수 없습니다.");
        gameId=match[1];
        if(!UUID.test(gameId)) return error(400,"INVALID_REQUEST","gameId는 UUID여야 합니다.");
        if(request.method==="GET" && !match[2]) operation="get";
        else if(request.method==="POST" && match[2]) operation=({"pause":"pause","resume":"resume","abandon":"abandon","initiative":"initiative","rolls":"roll","ai/rolls":"ai-roll","quiz-answers":"answer","quiz-timeouts":"quiz-timeout","ai/quiz-answers":"ai-answer","purchases":"purchase","ai/purchases":"ai-purchase","turn-completions":"complete-turn","ai/turn-completions":"ai-complete-turn","island-skips":"island-skip","ai/island-skips":"ai-island-skip"})[match[2]];
        else return error(405,"METHOD_NOT_ALLOWED","지원하지 않는 메서드입니다.");
      }
      const authorization=request.headers.get("Authorization");
      if(!authorization || !/^Bearer [^\s]+$/i.test(authorization))
        return error(401,"AUTH_REQUIRED","사용자 인증 토큰이 필요합니다.");
      let body={},key=null;
      if(request.method==="POST"){
        key=request.headers.get("Idempotency-Key");
        if(!key || !UUID.test(key)) return error(400,"INVALID_REQUEST","Idempotency-Key UUID가 필요합니다.");
        if(!(request.headers.get("Content-Type")??"").toLowerCase().startsWith("application/json"))
          return error(400,"INVALID_REQUEST","application/json 요청이 필요합니다.");
        // Content-Length가 없거나 거짓인 경우에도 읽는 바이트 수를 제한합니다.
        const reader=request.body?.getReader();
        let bytes=0,text="";
        if(reader){
          const decoder=new TextDecoder();
          while(true){
            const part=await reader.read();
            if(part.done) break;
            bytes+=part.value.byteLength;
            if(bytes>16384){await reader.cancel();return error(413,"REQUEST_TOO_LARGE","요청이 너무 큽니다.");}
            text+=decoder.decode(part.value,{stream:true});
          }
          text+=decoder.decode();
        }
        try{body=JSON.parse(text);}catch{return error(400,"INVALID_REQUEST","올바른 JSON이 아닙니다.");}
        const quizOperation=["answer","quiz-timeout","ai-answer"].includes(operation);
        const turnOperation=["complete-turn","ai-complete-turn","island-skip","ai-island-skip"].includes(operation);
        const allowed=turnOperation?["expectedVersion","turnId"]:
          operation==="purchase"?["expectedVersion","tileId","action"]:
          operation==="ai-purchase"?["expectedVersion","tileId"]:operation==="create"?["mode","characterId"]:
          quizOperation?["expectedVersion","attemptId",...(operation==="answer"?["choiceId"]:[])]:["expectedVersion"];
        if(!body || Array.isArray(body) || typeof body!=="object" ||
           Object.keys(body).some(k=>!allowed.includes(k)))
          return error(400,"INVALID_REQUEST","요청 필드를 확인해 주세요.");
        if(operation==="create"){
          if(body.mode!=="AI" || typeof body.characterId!=="string" || !body.characterId)
            return error(400,"INVALID_REQUEST","AI 모드와 characterId가 필요합니다.");
        }else if(!Number.isSafeInteger(body.expectedVersion)||body.expectedVersion<0)
          return error(400,"INVALID_REQUEST","expectedVersion은 0 이상의 정수여야 합니다.");
      }
      if(["answer","quiz-timeout","ai-answer"].includes(operation)){
        if(typeof body.attemptId!=="string" || !UUID.test(body.attemptId) ||
           (operation==="answer" && (!Number.isInteger(body.choiceId)||body.choiceId<1||body.choiceId>4)))
          return error(400,"INVALID_REQUEST","문항 ID와 선택지를 확인해 주세요.");
      }
      if(["purchase","ai-purchase"].includes(operation) &&
         (!Number.isInteger(body.tileId)||body.tileId<0||body.tileId>13||
          (operation==="purchase"&&!["BUY","PASS"].includes(body.action))))
        return error(400,"INVALID_REQUEST","구매할 칸과 선택을 확인해 주세요.");
      if(["complete-turn","ai-complete-turn","island-skip","ai-island-skip"].includes(operation) &&
         (typeof body.turnId!=="string"||!UUID.test(body.turnId)))
        return error(400,"INVALID_REQUEST","현재 차례 ID가 필요합니다.");
      // 토큰 내용을 단순 해독해서 신뢰하지 않습니다. 요청 본문에서 userId도 받지 않습니다.
      const auth=await fetcher(base+"/auth/v1/user",{
        headers:{apikey:publicKey,Authorization:authorization},
        signal:AbortSignal.timeout(10000),
      });
      if(auth.status===401||auth.status===403) return error(401,"AUTH_REQUIRED","인증 세션을 확인해 주세요.");
      if(!auth.ok) return error(503,"AUTH_UNAVAILABLE","인증 서버에 연결할 수 없습니다.");
      const user=await auth.json();
      if(!user?.id || !UUID.test(user.id)) return error(401,"AUTH_REQUIRED","사용자 인증을 확인할 수 없습니다.");
      const rpc=await fetcher(base+"/rest/v1/rpc/finpop_game_action",{
        method:"POST",headers:{
          apikey:serviceKey,
          // 이 어댑터는 Supabase 기본 legacy service_role JWT를 사용합니다.
          Authorization:"Bearer "+serviceKey,"Content-Type":"application/json",
        },
        body:JSON.stringify({p_user_id:user.id,p_operation:operation,p_game_id:gameId,p_request_key:key,p_body:body}),
        signal:AbortSignal.timeout(15000),
      });
      if(!rpc.ok) return error(503,"GAME_SERVICE_UNAVAILABLE","게임 서버 설정과 DB 함수를 확인해 주세요.");
      const result=await rpc.json();
      if(!Number.isInteger(result?.httpStatus)||result.httpStatus<200||result.httpStatus>599||!result.body)
        return error(502,"INVALID_SERVER_RESPONSE","게임 서버 응답이 올바르지 않습니다.");
      return Response.json(result.body,{status:result.httpStatus,headers:{"Cache-Control":"no-store"}});
    }catch{
      return error(503,"GAME_SERVICE_UNAVAILABLE","서버 통신 중 오류가 발생했습니다. 같은 요청 키로 재시도해 주세요.");
    }
  };
}
