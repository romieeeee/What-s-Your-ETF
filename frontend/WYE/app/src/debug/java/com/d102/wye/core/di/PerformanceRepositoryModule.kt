package com.d102.wye.core.di

import com.d102.wye.data.repository.fake.FakeEtfRepository
import com.d102.wye.data.repository.fake.FakePortfolioRepository
import com.d102.wye.data.repository.fake.FakeSimulationRepository
import com.d102.wye.domain.repository.EtfRepository
import com.d102.wye.domain.repository.PortfolioRepository
import com.d102.wye.domain.repository.SimulationRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 서버 없이 성능 테스트를 실행하기 위한 Debug 전용 Repository 바인딩. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PerformanceRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindEtfRepository(impl: FakeEtfRepository): EtfRepository

    @Binds
    @Singleton
    abstract fun bindSimulationRepository(impl: FakeSimulationRepository): SimulationRepository

    @Binds
    @Singleton
    abstract fun bindPortfolioRepository(impl: FakePortfolioRepository): PortfolioRepository
}