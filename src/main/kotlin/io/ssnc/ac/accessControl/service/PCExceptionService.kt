package io.ssnc.ac.accessControl.service;

//import io.ssnc.ac.accessControl.entity.PCExceptionResult;
import io.ssnc.ac.accessControl.exception.NotFoundException
import io.ssnc.ac.accessControl.entity.*
import io.ssnc.ac.accessControl.entity.request.AccessControlRequest
import io.ssnc.ac.accessControl.entity.request.LogRequest
import io.ssnc.ac.accessControl.entity.request.StoreRule
import io.ssnc.ac.accessControl.entity.response.IcatResult
import io.ssnc.ac.accessControl.repository.*
import io.ssnc.ac.accessControl.util.DateUtil
import mu.KLogging
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.File

import javax.persistence.EntityManager;
import javax.transaction.Transactional;
import javax.persistence.StoredProcedureQuery

@Service
class PCExceptionService {
    companion object : KLogging()

    @Autowired
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var pcIcatBasicRepository: PCIcatBasicRepository

    @Autowired
    lateinit var pcIcatDefaultRepository: PCIcatDefaultRepository

    @Autowired
    lateinit var logRepository: LogRepository

    @Autowired
    lateinit var pcBasicRepository: PcBasicRepository

    @Autowired
    lateinit var ssaClassstackVerinfoRepository: SsaClassstackVerinfoRepository

    @Autowired
    lateinit var incopsPolicyRepository: IncopsPolicyRepository

    @Autowired
    lateinit var pfwStoreRuleRepository: PfwStoreRuleRepository

    @Autowired
    lateinit var incopsPcexceptionLogRepository: IncopsPcexceptionLogRepository

    @Autowired
    lateinit var pcExceptionRepository: PcExceptionRepository

    @Autowired
    lateinit var pcPgmListRepository: PcPgmListRepository

    @Autowired
    lateinit var pcPgmExceptionRepository: PcPgmExceptionRepository

    @Transactional
    fun getVersion(): SsaClaastackVerinfo {
        var results = ssaClassstackVerinfoRepository.findAll()
        return results.last()
    }

    @Transactional
    fun search(serial: String) : IcatResult? {

        val query: StoredProcedureQuery = entityManager
            .createNamedStoredProcedureQuery("getIcatCtrlDefault")
            .setParameter("serial", serial)

        val defaultResult : IcatCtrlDefault = query.resultList.get(0) as IcatCtrlDefault
        if (defaultResult.empno.isNullOrEmpty()) {
            throw NotFoundException("empno is null")
        }

        val resultBasic = ArrayList<IcatCtrlBase>()

        if (defaultResult.ctrlOnoff.equals("ON")) {
            val query1: StoredProcedureQuery = entityManager
                .createNamedStoredProcedureQuery("getIcatCtrlBasic")

            val basics = query1.resultList

            basics.forEach { it ->
                val icb = it as IcatCtrlBase
                val basic = IcatCtrlBase(ctrlGubun = icb.ctrlGubun, expType = icb.expType, expVal1 = icb.expVal1, expVal2 = icb.expVal2)
                resultBasic.add(basic)
            }
        }
        val resultException = ArrayList<IcatException>()
        val query2: StoredProcedureQuery = entityManager
            .createNamedStoredProcedureQuery("getIcatException")
            .setParameter("serial", serial)

        val exceptions = query2.resultList

        exceptions.forEach { it ->
            val ie = it as IcatException
            val exception = IcatException(serial = ie.serial, ctrlGubun = ie.ctrlGubun,
                                          expType = ie.expType, expVal1 = ie.expVal1, expVal2 = ie.expVal2,
                                          allowFromdate = ie.allowFromdate, allowTodate = ie.allowTodate)
            resultException.add(exception)
        }


        val result = IcatResult(serial = defaultResult.serial,
            empno = defaultResult.empno,
            hname = defaultResult.hname,
            locatenm = defaultResult.locatenm,
            pc_gubun = defaultResult.pcGubun,
            ctrl_onoff = defaultResult.ctrlOnoff,
            logging_onoff = defaultResult.loggingOnoff,
            icat_base = resultBasic,
            icat_exception = resultException)

        return result
    }

    @Transactional
    fun createLog(request: LogRequest) : String? {
        logger.info("Method=create, createLog={}", request)
        val logpk = LogPK(
            eventTime = DateUtil.nowDateTimeString,
            serial = request.serial, type = request.type, attFilename = request.att_filename)
        val pcbasic = pcBasicRepository.findBySerial(request.serial)
        val log = Log(logPk = logpk,
            empno = pcbasic.empno, hname = pcbasic.hname, ip = pcbasic.ipAddr,
            sdeptnm = pcbasic.sdeptnm, deptcode = pcbasic.deptcode, locatenm = pcbasic.locatenm, madecode = pcbasic.madecode)
        logRepository.save(log)

        return log.logPk!!.eventTime
    }

    fun checkFile(file: File) {
        if (!file.isFile) {
            throw IllegalStateException("The path '${file.path}' is not a valid file")
        }
    }

    fun createAccessControls(request: AccessControlRequest) {
        if (!request.storages.isNullOrEmpty()){
            createStorage(request)
        }
        if (!request.programs.isNullOrEmpty()){
            createProgram(request)
        }

        if (!request.devices.isNullOrEmpty()){
            createDevices(request)
        }
    }

    fun createStorage(request: AccessControlRequest) {
        //정책 조회
        var storeNoUse = 0
        val ip_pk = IncopsPolicyPK(locatenm = "ALL", pcGbun = "Z", gubun = "STORE_NOUSE")
        val policy = incopsPolicyRepository.findByPk(ip_pk)
        storeNoUse = policy.value2!!

        val basic = pcBasicRepository.findBySerial(request.serial)

        request.storages!!.forEach { storage ->
            if (storage.devName.equals("MOBILE-RW")) {
                when (storage.allowType) {
                    "A", "R" -> storage.allowType = "Y"
                    "B" -> storage.allowType = "N"
                }
                storeNoUse = 0
            }

            //기존 정책 등록 여부 확인
            var actiongb : String? = null
            val psr_pk = PfwStoreRulePk(serial = request.serial, devName = storage.devName)

            pfwStoreRuleRepository.findByPkAndGrpGubun(psr_pk, "P")?.let { exits ->
                exits.allowType = storage.allowType
                exits.allowLog = request.allowLog
                exits.allowDesc = storage.allowDesc
                exits.allowFromdate = if ( exits.allowFromdate!!.toLong() < storage.allowStartDate.toLong() ) exits.allowFromdate else storage.allowStartDate
                exits.allowTodate = storage.allowEndDate
                exits.storeNouse = storeNoUse
                exits.allowDate = DateUtil.nowDateToYYYYMMDD
                exits.regEmpno = request.regEmpno
                exits.deptcode = (if (request.grpGubun == "P") basic.deptcode else null).toString()
                exits.lastuseTime = DateUtil.nowDateToYYYYMMDDHHMM

                pfwStoreRuleRepository.save(exits)
                actiongb = "U"
            } ?: run {
                val new = PfwStoreRule(pk = psr_pk,
                    empno = (if (request.grpGubun == "P") basic.empno else null).toString(),
                    hname = (if (request.grpGubun == "P") basic.hname else null).toString(),
                    sdeptnm = (if (request.grpGubun == "P") basic.sdeptnm else null).toString(),
                    indeptnm = (if (request.grpGubun == "P") basic.indeptnm else null).toString(),
                    locatenm = (if (request.grpGubun == "P") basic.locatenm else null).toString(),
                    allowType = storage.allowType,
                    allowLog = request.allowLog,
                    allowDesc = storage.allowDesc,
                    allowFromdate = storage.allowStartDate,
                    allowTodate = storage.allowEndDate,
                    storeNouse = storeNoUse,
                    allowDate = DateUtil.nowDateToYYYYMMDD,
                    regEmpno = request.regEmpno,
                    deptcode = (if (request.grpGubun == "P") basic.deptcode else null).toString(),
                    lastuseTime = DateUtil.nowDateToYYYYMMDDHHMM ,
                    grpGubun = request.grpGubun,
                    removalAllow = null
                )
                pfwStoreRuleRepository.save(new)
                actiongb = "I"
            }

            // 로그 디렉토리 적
            val log_pk = IncopsPcexceptionLogPK(changeTime = DateUtil.nowDateTimeString,
                actiongb = actiongb, serial = request.serial, poGubun = "EXC_WALL01", devName = storage.devName)

            val log = IncopsPcexceptionLog(pk = log_pk,
                empno = (if (request.grpGubun == "P") basic.empno else null).toString(),
                hname = (if (request.grpGubun == "P") basic.hname else null).toString(),
                poGubundtl = storage.devName,
                sdeptnm = (if (request.grpGubun == "P") basic.sdeptnm else null).toString(),
                deptcode = (if (request.grpGubun == "P") basic.deptcode else null).toString(),
                indeptnm = (if (request.grpGubun == "P") basic.indeptnm else null).toString(),
                locatenm = (if (request.grpGubun == "P") basic.locatenm else null).toString(),
                pcGubun = (if (request.grpGubun == "P") basic.pcGubun else null).toString(),
                grpGubun = request.grpGubun,
                allowedDate = DateUtil.nowDateTimeString,
                allowedDesc = storage.allowDesc,
                allowFromdate = storage.allowStartDate,
                allowTodate = storage.allowEndDate,
                ruleNo = null,
                portName = null,
                gubun = storeNoUse.toString(),
                allowVal = storage.allowType,
                logVal = request.allowLog,
                changer = request.regEmpno,
                remark1 = null, remark2 = null
            )
            //log 저장
            incopsPcexceptionLogRepository.save(log)

            //MOBILE 장치 처리 추가
            if (storage.devName.equals("MOBILE-RW")) {
                val mobileTypes: Array<String> = arrayOf("T", "M", "9")
                for (i in mobileTypes.indices) {
                    val pce_pk = PcExceptionPk(serial = request.serial, gubun = mobileTypes[i])
                    pcExceptionRepository.findByPk(pce_pk)?.let { exits_pc ->
                        exits_pc.expDate = DateUtil.nowDateTimeString
                        exits_pc.expDesc = storage.allowDesc
                        exits_pc.value1 = if (storage.allowType == "Y") 0 else 1
                        exits_pc.allowFromdate = if ( exits_pc.allowFromdate!!.toLong() < storage.allowStartDate.toLong() ) exits_pc.allowFromdate else storage.allowStartDate
                        exits_pc.allowTodate = storage.allowEndDate
                        exits_pc.regEmpno = request.regEmpno
                        exits_pc.grpGubun = request.grpGubun
                        actiongb = "U"

                        pcExceptionRepository.save(exits_pc)
                    } ?: run {
                        val new_pce = PcException(pk = pce_pk,
                            expDate = DateUtil.nowDateTimeString,
                            expDesc = storage.allowDesc,
                            value1 = if (storage.allowType == "Y") 0 else 1,
                            allowFromdate = storage.allowStartDate,
                            allowTodate = storage.allowEndDate,
                            regEmpno = request.regEmpno,
                            grpGubun = request.grpGubun
                        )
                        actiongb = "I"
                        pcExceptionRepository.save(new_pce)
                    }
                    //log 저장
                    log_pk.devName = mobileTypes[i]
                    log_pk.actiongb = actiongb
                    log.poGubundtl = mobileTypes[i]
                    log.gubun = mobileTypes[i]
                    log.allowVal = (if (storage.allowType == "Y") 0 else 1).toString()
                    incopsPcexceptionLogRepository.save(log)
                }
            }
        }
    }

    fun createProgram(request: AccessControlRequest) {
        request.programs!!.forEach { program ->
            //
            if (program.allowType == "A") program.allowType = "0"

        }
    }

    fun createDevices(request: AccessControlRequest) {
        var actiongb : String? = null

        val basic = pcBasicRepository.findBySerial(request.serial)

        request.devices!!.forEach { device ->
            device.allowType =
                when (device.allowType) {
                    "A" -> "0"
                    "B" -> "1"
                    "E" -> "2"
                    "T" -> "3"
                    else -> "0"
                }
            // 1. 기존 데이터 fetch
            val exits_pk = PcExceptionPk(serial = request.serial, gubun = device.devName)
            pcExceptionRepository.findByPk(exits_pk)?.let { exits ->
                exits.expDate = DateUtil.nowDateTimeString
                exits.expDesc = device.allowDesc
                exits.value1 = if (device.allowType == "Y") 0 else 1
                exits.allowFromdate = if ( device.allowEndDate!!.toLong() < device.allowStartDate.toLong() ) exits.allowFromdate else device.allowStartDate
                exits.allowTodate = device.allowEndDate
                exits.regEmpno = request.regEmpno
                exits.grpGubun = request.grpGubun
                actiongb = "U"
                pcExceptionRepository.save(exits)
            } ?: run {
                val new_pce = PcException(pk = exits_pk,
                    expDate = DateUtil.nowDateTimeString,
                    expDesc = device.allowDesc,
                    value1 = device.allowType.toInt(),
                    allowFromdate = device.allowStartDate,
                    allowTodate = device.allowEndDate,
                    regEmpno = request.regEmpno,
                    grpGubun = request.grpGubun
                )
                actiongb = "I"
                pcExceptionRepository.save(new_pce)
            }

            val log_pk = IncopsPcexceptionLogPK(changeTime = DateUtil.nowDateTimeString,
                actiongb = actiongb, serial = request.serial,
                poGubun = when(device.devName) {
                    "B","S","F","V","E","5","6","7","8","X" -> "EXC_CLINIC21"
                    else -> "EXC_WALL01"
                }, devName = device.devName)

            val log = IncopsPcexceptionLog(pk = log_pk,
                empno = (if (request.grpGubun == "P") basic.empno else null).toString(),
                hname = (if (request.grpGubun == "P") basic.hname else null).toString(),
                poGubundtl = device.devName,
                sdeptnm = (if (request.grpGubun == "P") basic.sdeptnm else null).toString(),
                deptcode = (if (request.grpGubun == "P") basic.deptcode else null).toString(),
                indeptnm = (if (request.grpGubun == "P") basic.indeptnm else null).toString(),
                locatenm = (if (request.grpGubun == "P") basic.locatenm else null).toString(),
                pcGubun = (if (request.grpGubun == "P") basic.pcGubun else null).toString(),
                grpGubun = request.grpGubun,
                allowedDate = DateUtil.nowDateTimeString,
                allowedDesc = device.allowDesc,
                allowFromdate = device.allowStartDate,
                allowTodate = device.allowEndDate,
                ruleNo = null,
                portName = null,
                gubun = device.devName,
                allowVal = device.allowType,
                logVal = request.allowLog,
                changer = request.regEmpno,
                remark1 = null, remark2 = null
            )
            //log 저장
            incopsPcexceptionLogRepository.save(log)

            if (device.devName == "M") {
                val storeRule = StoreRule(serial = request.serial,
                    grpGubun = request.grpGubun, allowLog = request.allowLog, regEmpno = request.regEmpno,
                    allowStartDate = device.allowStartDate, allowEndDate = device.allowEndDate,
                    allowType = when(device.allowType) {
                        "0" -> "Y"
                        else -> "N"
                    },
                    pgmLists = device.pgmLists,
                    allowDesc = device.allowDesc, devName = "M")
                createStorage1(storeRule)
                if (!device.pgmLists.isNullOrEmpty())
                    createPgm(storeRule)
            }
        }
    }

    fun createStorage1(request: StoreRule) {
        //정책 조회
        var storeNoUse = 0
        val ip_pk = IncopsPolicyPK(locatenm = "ALL", pcGbun = "Z", gubun = "STORE_NOUSE")
        val policy = incopsPolicyRepository.findByPk(ip_pk)
        storeNoUse = policy.value2!!

        val basic = pcBasicRepository.findBySerial(request.serial)

        if (request.devName.equals("MOBILE-RW")|| request.devName.equals("M")) {
            when (request.allowType) {
                "A", "R" -> request.allowType = "Y"
                "B" -> request.allowType = "N"
            }
            storeNoUse = 0
        }

        //기존 정책 등록 여부 확인
        var actiongb : String? = null
        val psr_pk = PfwStoreRulePk(serial = request.serial, devName = if (request.devName != "M") request.devName else "MOBILE-RW" )

        pfwStoreRuleRepository.findByPkAndGrpGubun(psr_pk, "P")?.let { exits ->
            exits.allowType = request.allowType
            exits.allowLog = request.allowLog
            exits.allowDesc = request.allowDesc
            exits.allowFromdate = if ( exits.allowFromdate!!.toLong() < request.allowStartDate.toLong() ) exits.allowFromdate else request.allowStartDate
            exits.allowTodate = request.allowEndDate
            exits.storeNouse = storeNoUse
            exits.allowDate = DateUtil.nowDateToYYYYMMDD
            exits.regEmpno = request.regEmpno
            exits.deptcode = (if (request.grpGubun == "P") basic.deptcode else null).toString()
            exits.lastuseTime = DateUtil.nowDateToYYYYMMDDHHMM

            pfwStoreRuleRepository.save(exits)
            actiongb = "U"
        } ?: run {
            val new = PfwStoreRule(pk = psr_pk,
                empno = (if (request.grpGubun == "P") basic.empno else null).toString(),
                hname = (if (request.grpGubun == "P") basic.hname else null).toString(),
                sdeptnm = (if (request.grpGubun == "P") basic.sdeptnm else null).toString(),
                indeptnm = (if (request.grpGubun == "P") basic.indeptnm else null).toString(),
                locatenm = (if (request.grpGubun == "P") basic.locatenm else null).toString(),
                allowType = request.allowType,
                allowLog = request.allowLog,
                allowDesc = request.allowDesc,
                allowFromdate = request.allowStartDate,
                allowTodate = request.allowEndDate,
                storeNouse = storeNoUse,
                allowDate = DateUtil.nowDateToYYYYMMDD,
                regEmpno = request.regEmpno,
                deptcode = (if (request.grpGubun == "P") basic.deptcode else null).toString(),
                lastuseTime = DateUtil.nowDateToYYYYMMDDHHMM ,
                grpGubun = request.grpGubun,
                removalAllow = null
            )
            pfwStoreRuleRepository.save(new)
            actiongb = "I"
        }

        // 로그 디렉토리 적
        val log_pk = IncopsPcexceptionLogPK(changeTime = DateUtil.nowDateTimeString,
            actiongb = actiongb, serial = request.serial, poGubun = "EXC_WALL01", devName = if (request.devName != "M") request.devName else "MOBILE-RW")

        val log = IncopsPcexceptionLog(pk = log_pk,
            empno = (if (request.grpGubun == "P") basic.empno else null).toString(),
            hname = (if (request.grpGubun == "P") basic.hname else null).toString(),
            poGubundtl = if (request.devName != "M") request.devName else "MOBILE-RW",
            sdeptnm = (if (request.grpGubun == "P") basic.sdeptnm else null).toString(),
            deptcode = (if (request.grpGubun == "P") basic.deptcode else null).toString(),
            indeptnm = (if (request.grpGubun == "P") basic.indeptnm else null).toString(),
            locatenm = (if (request.grpGubun == "P") basic.locatenm else null).toString(),
            pcGubun = (if (request.grpGubun == "P") basic.pcGubun else null).toString(),
            grpGubun = request.grpGubun,
            allowedDate = DateUtil.nowDateTimeString,
            allowedDesc = request.allowDesc,
            allowFromdate = request.allowStartDate,
            allowTodate = request.allowEndDate,
            ruleNo = null,
            portName = null,
            gubun = storeNoUse.toString(),
            allowVal = request.allowType,
            logVal = request.allowLog,
            changer = request.regEmpno,
            remark1 = null, remark2 = null
        )
        //log 저장
        incopsPcexceptionLogRepository.save(log)

        //MOBILE 장치 처리 추가
        if (request.devName.equals("MOBILE-RW")) {
            var mobileTypes: Array<String> = arrayOf("T", "M", "9")

            for (i in mobileTypes.indices) {
                val pce_pk = PcExceptionPk(serial = request.serial, gubun = mobileTypes[i])
                pcExceptionRepository.findByPk(pce_pk)?.let { exits_pc ->
                    exits_pc.expDate = DateUtil.nowDateTimeString
                    exits_pc.expDesc = request.allowDesc
                    exits_pc.value1 = if (request.allowType == "Y") 0 else 1
                    exits_pc.allowFromdate = if ( exits_pc.allowFromdate!!.toLong() < request.allowStartDate.toLong() ) exits_pc.allowFromdate else request.allowStartDate
                    exits_pc.allowTodate = request.allowEndDate
                    exits_pc.regEmpno = request.regEmpno
                    exits_pc.grpGubun = request.grpGubun
                    actiongb = "U"

                    pcExceptionRepository.save(exits_pc)
                } ?: run {
                    val new_pce = PcException(pk = pce_pk,
                        expDate = DateUtil.nowDateTimeString,
                        expDesc = request.allowDesc,
                        value1 = if (request.allowType == "Y") 0 else 1,
                        allowFromdate = request.allowStartDate,
                        allowTodate = request.allowEndDate,
                        regEmpno = request.regEmpno,
                        grpGubun = request.grpGubun
                    )
                    actiongb = "I"
                    pcExceptionRepository.save(new_pce)
                }
                //log 저장
                log_pk.devName = mobileTypes[i]
                log_pk.actiongb = actiongb
                log.poGubundtl = mobileTypes[i]
                log.gubun = mobileTypes[i]
                log.allowVal = (if (request.allowType == "Y") 0 else 1).toString()
                incopsPcexceptionLogRepository.save(log)
            }
        }
    }

    fun createPgm(request: StoreRule) {
        var actiongb: String? = null
        val basic = pcBasicRepository.findBySerial(request.serial)

        request.pgmLists!!.forEach { filename ->
            pcPgmListRepository.findTopByFileName(filename)?.let { pgm ->
                val pce_pk = PcPgmExceptionPk(seq = pgm.seq, serial = request.serial)
                pcPgmExceptionRepository.findByPk(pce_pk)?.let { exist ->
                    when (request.allowType) {
                        "1" -> {
                            pcPgmExceptionRepository.deleteByPk(pce_pk)
                            actiongb = "D"
                        }
                        else -> {
                            exist.expDate = DateUtil.nowDateTimeString
                            exist.expDesc = request.allowDesc
                            exist.allowFromdate = request.allowStartDate
                            exist.allowTodate = request.allowEndDate
                            exist.grpGubun = request.grpGubun
                            exist.regEmpno = request.regEmpno
                            pcPgmExceptionRepository.save(exist)
                            actiongb = "U"
                        }
                    }
                } ?: run {
                    if (request.allowType != "1") {
                        val new = PcPgmException(
                            pk = pce_pk,
                            expDate = DateUtil.nowDateTimeString,
                            expDesc = request.allowDesc,
                            allowTodate = request.allowEndDate,
                            allowFromdate = request.allowStartDate,
                            regEmpno = request.regEmpno,
                            grpGubun = request.grpGubun
                        )
                        pcPgmExceptionRepository.save(new)
                        actiongb = "I"
                    }
                }

                val log_pk = IncopsPcexceptionLogPK(changeTime = DateUtil.nowDateTimeString,
                    actiongb = actiongb, serial = request.serial, poGubun = "EXC_WALL02", devName = pgm.pgmName)

                val log = IncopsPcexceptionLog(pk = log_pk,
                    empno = (if (request.grpGubun == "P") basic.empno else null).toString(),
                    hname = (if (request.grpGubun == "P") basic.hname else null).toString(),
                    poGubundtl = "PUBLIC_PRGM",
                    sdeptnm = (if (request.grpGubun == "P") basic.sdeptnm else null).toString(),
                    deptcode = (if (request.grpGubun == "P") basic.deptcode else null).toString(),
                    indeptnm = (if (request.grpGubun == "P") basic.indeptnm else null).toString(),
                    locatenm = (if (request.grpGubun == "P") basic.locatenm else null).toString(),
                    pcGubun = (if (request.grpGubun == "P") basic.pcGubun else null).toString(),
                    grpGubun = request.grpGubun,
                    allowedDate = DateUtil.nowDateTimeString,
                    allowedDesc = request.allowDesc,
                    allowFromdate = request.allowStartDate,
                    allowTodate = request.allowEndDate,
                    ruleNo = null,
                    portName = null,
                    gubun = null,
                    allowVal = request.allowType,
                    logVal = request.allowLog,
                    changer = request.regEmpno,
                    remark1 = pgm.pgmName, remark2 = pgm.fileName
                )
                //log 저장
                incopsPcexceptionLogRepository.save(log)
            } ?: run {

            }
        }
    }


}
