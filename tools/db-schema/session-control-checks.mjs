import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

export async function validateSessionControls({db,test,rpc,fixedDice,ids}) {
 const upgrade=await readFile(new URL('../../docs/api/game-session-controls-update.sql',import.meta.url),'utf8');
 await db.exec(upgrade);
 async function prepare({actor='P1',quiz=false}={}) {
  await db.exec('truncate public.game_sessions cascade');
  await db.query('update public.users set completed_game_count=0 where id=$1',[ids.u1]);
  const created=await rpc('create',null,{mode:'AI',characterId:'CHEESE'});
  assert.equal(created.httpStatus,200);
  const id=created.body.data.gameId;
  if(quiz || actor==='P2') {
   const turn=(await db.query('insert into public.game_turns(game_id,player_id) values($1,$2) returning id',[id,actor])).rows[0].id;
   await db.query("update public.game_sessions set phase='TURN_READY',state_version=2,current_player_id=$2,current_turn_id=$3 where id=$1",[id,actor,turn]);
   if(quiz){await fixedDice(1);assert.equal((await rpc(actor==='P1'?'roll':'ai-roll',id,{expectedVersion:2})).httpStatus,200);}
  }
  const get=async()=>(await rpc('get',id,{},null)).body.data;
  const invoke=async(op,body,key=crypto.randomUUID(),user=ids.u1)=>rpc(op,id,body??{expectedVersion:(await get()).stateVersion},key,user);
  return {id,get,invoke};
 }
 await test('세션 SQL 재적용은 기존 상태·계정·권한을 유지',async()=>{
  const f=await prepare();const before=await f.get();await db.exec(upgrade);
  assert.deepEqual(await f.get(),before);
  for(const role of ['anon','authenticated'])assert.equal((await db.query("select has_function_privilege($1,'public.finpop_game_action(uuid,text,uuid,uuid,jsonb)','EXECUTE') allowed",[role])).rows[0].allowed,false);
 });
 await test('선공 전 일시정지·재개는 차례·잔액·턴을 유지',async()=>{
  const f=await prepare();const before=await f.get();
  const paused=await f.invoke('pause');assert.equal(paused.body.data.status,'PAUSED');assert.equal(paused.body.data.pausedPhase,'INITIATIVE_PENDING');
  assert.ok(paused.body.data.pausedAt);assert.equal(paused.body.data.remainingQuizMs,null);
  const resumed=await f.invoke('resume');assert.equal(resumed.body.data.status,'ACTIVE');assert.equal(resumed.body.data.phase,before.phase);
  assert.deepEqual(resumed.body.data.players,before.players);
 });
 await test('퀴즈 남은 시간 저장과 동일 문항 재개',async()=>{
  const f=await prepare({quiz:true});const initial=await f.get();const attempt=initial.pendingQuiz.attemptId;
  await db.query("update public.game_quiz_attempts set issued_at=clock_timestamp()-interval '8 seconds',deadline_at=clock_timestamp()+interval '12 seconds' where id=$1",[attempt]);
  const paused=await f.invoke('pause');assert.equal(paused.httpStatus,200);
  const frozen=paused.body.data.remainingQuizMs;assert.ok(frozen>0 && frozen<=12000);
  // 실제로 기다리는 대신 저장된 마감 시각을 과거로 옮겨 일시정지 중 경과를 재현합니다.
  await db.query("update public.game_quiz_attempts set issued_at=clock_timestamp()-interval '60 seconds',deadline_at=clock_timestamp()-interval '40 seconds' where id=$1",[attempt]);
  const fresh=await f.get();assert.equal(fresh.remainingQuizMs,frozen);
  const resumed=await f.invoke('resume');assert.equal(resumed.httpStatus,200);
  assert.equal(resumed.body.data.pendingQuiz.attemptId,attempt);assert.deepEqual(resumed.body.data.pendingQuiz.choices,initial.pendingQuiz.choices);
  const millis=(await db.query('select extract(epoch from (deadline_at-clock_timestamp()))*1000 as ms,remaining_millis from public.game_quiz_attempts where id=$1',[attempt])).rows[0];
  assert.ok(Number(millis.ms)>frozen-1500 && Number(millis.ms)<=frozen);assert.equal(millis.remaining_millis,null);
 });
 await test('마감된 퀴즈는 일시정지로 연장하지 않고 시간 초과 확정',async()=>{
  const f=await prepare({quiz:true});const g=await f.get();
  await db.query("update public.game_quiz_attempts set issued_at=clock_timestamp()-interval '30 seconds',deadline_at=clock_timestamp()-interval '10 seconds' where id=$1",[g.pendingQuiz.attemptId]);
  const paused=await f.invoke('pause');assert.equal(paused.httpStatus,200);assert.equal(paused.body.data.status,'PAUSED');
  assert.equal(paused.body.data.phase,'TURN_REVIEW');assert.equal(paused.body.data.lastQuizResult.resolution,'TIMEOUT');assert.equal(paused.body.data.pendingQuiz,null);
 });
 await test('일시정지 중 답변·시간 초과·AI 이동·선공 요청 차단',async()=>{
  const f=await prepare({quiz:true});const before=await f.get();await f.invoke('pause');const paused=await f.get();
  for(const [op,extra] of [['answer',{attemptId:before.pendingQuiz.attemptId,choiceId:1}],['quiz-timeout',{attemptId:before.pendingQuiz.attemptId}],['ai-roll',{}],['initiative',{}]]) {
   const r=await f.invoke(op,{expectedVersion:paused.stateVersion,...extra});assert.equal(r.body.error.code,'GAME_NOT_ACTIVE');
  }
  assert.deepEqual(await f.get(),paused);
 });
 await test('일시정지 중 새 게임 생성 차단 및 재개 전 상태 유지',async()=>{
  const f=await prepare();await f.invoke('pause');const r=await rpc('create',null,{mode:'AI',characterId:'CHEESE'});
  assert.equal(r.body.error.code,'ACTIVE_GAME_EXISTS');assert.equal(r.body.error.details.activeGameId,f.id);
 });
 await test('상태 변경 세 종류는 같은 키로 최초 응답을 반환',async()=>{
  const f=await prepare({quiz:true});
  for(const op of ['pause','resume','abandon']) {
   const body={expectedVersion:(await f.get()).stateVersion};const key=crypto.randomUUID();
   const first=await f.invoke(op,body,key);assert.equal(first.httpStatus,200);
   assert.deepEqual(await f.invoke(op,body,key),first);
   assert.equal((await f.invoke(op,{expectedVersion:body.expectedVersion+1},key)).body.error.code,'IDEMPOTENCY_CONFLICT');
  }
 });
 await test('새 키로 중복 일시정지·재개해도 상태를 재처리하지 않음',async()=>{
  const f=await prepare();await f.invoke('pause');const before=await f.get();
  assert.equal((await f.invoke('pause')).body.error.code,'PAUSE_NOT_ALLOWED');assert.deepEqual(await f.get(),before);
  await f.invoke('resume');assert.equal((await f.invoke('resume')).body.error.code,'GAME_NOT_PAUSED');
 });
 await test('오래된 버전·타인 게임 변경 차단',async()=>{
  const f=await prepare();const body={expectedVersion:1};
  assert.equal((await f.invoke('pause',body,crypto.randomUUID(),ids.u2)).body.error.code,'FORBIDDEN');
  await f.invoke('pause');assert.equal((await f.invoke('resume',body)).body.error.code,'STATE_CONFLICT');
 });
 await test('진행·일시정지 양쪽에서 포기하면 보상 없이 활성 슬롯 해제',async()=>{
  for(const paused of [false,true]) {
   const f=await prepare({quiz:true});if(paused)await f.invoke('pause');
   const profile=(await db.query('select * from public.users where id=$1',[ids.u1])).rows[0];
   const result=await f.invoke('abandon');assert.equal(result.httpStatus,200);
   const g=result.body.data;assert.equal(g.status,'ABANDONED');assert.equal(g.phase,'ABANDONED');assert.ok(g.abandonedAt);
   assert.equal(g.pendingQuiz,null);assert.equal(g.currentTurnId,null);assert.equal(g.rewardGranted,false);assert.equal(g.completedCountIncremented,false);
   assert.deepEqual((await db.query('select * from public.users where id=$1',[ids.u1])).rows[0],profile);
   assert.equal((await f.invoke('abandon')).body.error.code,'GAME_ALREADY_FINISHED');
   assert.equal((await f.invoke('resume')).body.error.code,'GAME_NOT_PAUSED');
   assert.equal((await rpc('create',null,{mode:'AI',characterId:'CHEESE'})).httpStatus,200);
  }
 });
 await test('AI 차례의 일시정지·재개는 현재 차례를 보존',async()=>{
  const f=await prepare({actor:'P2'});const before=await f.get();await f.invoke('pause');const r=await f.invoke('resume');
  assert.equal(r.body.data.currentPlayerId,'P2');assert.equal(r.body.data.currentTurnId,before.currentTurnId);assert.deepEqual(r.body.data.players,before.players);
 });
}
